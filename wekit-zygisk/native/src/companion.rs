// ─────────────────────────────────────────────────────────────────────────────
// Zygisk companion process handler
//
// The companion runs as root before app specialization.  It checks the
// injection allow-list, then either answers a simple enable/disable query or
// creates an abstract Unix socket and double-forks a Telegram snapshot worker
// that is adopted by init, keeping it alive after the companion exits.
// ─────────────────────────────────────────────────────────────────────────────

use crate::protocol::{
    COMPANION_DISABLED, COMPANION_ENABLED, COMPANION_ERROR, COMPANION_REQUEST_ENABLED,
    COMPANION_REQUEST_TELEGRAM_SESSION, TELEGRAM_REQUEST_COPY_DATABASE, TELEGRAM_REQUEST_DISCOVER,
    TELEGRAM_RESPONSE_OK, read_i32_from_fd, read_string_from_fd, read_u8_from_fd,
    write_error_frame, write_string_to_fd, write_u8_to_fd, write_u16_to_fd, write_u64_to_fd,
};
use crate::{loge, logi};
use libc::{AF_UNIX, SOCK_STREAM, c_int, sockaddr_un};
use std::fs;

const TARGETS_PATH: &str = "/data/adb/wekit/injection-targets.tsv";
const WECHAT_PACKAGE_PREFIX: &str = "com.tencent.mm";

// ── Allow-list ────────────────────────────────────────────────────────────────

fn is_enabled_target(uid: i32, process_name: &str) -> bool {
    let user_id = uid / 100_000;
    let content = match fs::read_to_string(TARGETS_PATH) {
        Ok(s) => s,
        Err(_) => return false,
    };
    for line in content.lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.splitn(4, '\t').collect();
        if parts.len() != 3 {
            continue;
        }
        let (row_user, pkg, enabled) = (parts[0], parts[1], parts[2]);
        if enabled != "1" {
            continue;
        }
        if !pkg.starts_with(WECHAT_PACKAGE_PREFIX) {
            continue;
        }
        let row_uid: i32 = match row_user.parse() {
            Ok(v) => v,
            Err(_) => continue,
        };
        if row_uid != user_id {
            continue;
        }
        if process_name == pkg || process_name.starts_with(&format!("{pkg}:")) {
            return true;
        }
    }
    false
}

// ── Request header ────────────────────────────────────────────────────────────

struct RequestHeader {
    request_type: u8,
    uid: i32,
    process_name: String,
}

fn read_header(sock: c_int) -> Option<RequestHeader> {
    let request_type = read_u8_from_fd(sock).ok()?;
    let uid = read_i32_from_fd(sock).ok()?;
    let process_name = read_string_from_fd(sock).ok()?;
    Some(RequestHeader {
        request_type,
        uid,
        process_name,
    })
}

// ── Abstract socket name ──────────────────────────────────────────────────────

fn random_nonce() -> u32 {
    let mut buf = [0u8; 4];
    let path = c"/dev/urandom";
    // SAFETY: standard POSIX open/read/close on /dev/urandom
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY) };
    if fd >= 0 {
        unsafe {
            libc::read(fd, buf.as_mut_ptr().cast(), 4);
            libc::close(fd);
        }
    }
    u32::from_ne_bytes(buf)
}

fn make_abstract_name(uid: i32) -> String {
    format!("wekit-tg-{uid}-{:08x}", random_nonce())
}

// ── Telegram worker ───────────────────────────────────────────────────────────

fn find_wechat_instances() -> Vec<String> {
    let mut pkgs = Vec::new();
    if let Ok(entries) = fs::read_dir("/data/data") {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with(WECHAT_PACKAGE_PREFIX) {
                pkgs.push(name);
            }
        }
    }
    pkgs
}

fn send_file_contents(fd: c_int, path: &str) {
    match fs::read(path) {
        Ok(bytes) => {
            let _ = write_u64_to_fd(fd, bytes.len() as u64);
            let mut sent = 0usize;
            while sent < bytes.len() {
                let n =
                    unsafe { libc::write(fd, bytes[sent..].as_ptr().cast(), bytes.len() - sent) };
                if n <= 0 {
                    break;
                }
                sent += n as usize;
            }
        }
        Err(_) => {
            let _ = write_u64_to_fd(fd, 0);
        }
    }
}

fn telegram_worker(server_fd: c_int) {
    loop {
        // SAFETY: standard accept() on our own server socket
        let client = unsafe { libc::accept(server_fd, std::ptr::null_mut(), std::ptr::null_mut()) };
        if client < 0 {
            continue;
        }
        let op = match read_u8_from_fd(client) {
            Ok(v) => v,
            Err(_) => {
                unsafe { libc::close(client) };
                continue;
            }
        };
        match op {
            TELEGRAM_REQUEST_DISCOVER => {
                let instances = find_wechat_instances();
                let count = instances.len().min(u16::MAX as usize) as u16;
                let _ = write_u8_to_fd(client, TELEGRAM_RESPONSE_OK);
                let _ = write_u16_to_fd(client, count);
                for pkg in &instances {
                    let _ = write_string_to_fd(client, pkg);
                }
            }
            TELEGRAM_REQUEST_COPY_DATABASE => {
                if let Ok(pkg) = read_string_from_fd(client) {
                    let _ = write_u8_to_fd(client, TELEGRAM_RESPONSE_OK);
                    let base = format!("/data/data/{pkg}/files/account.db");
                    send_file_contents(client, &base);
                    send_file_contents(client, &format!("{base}-wal"));
                    send_file_contents(client, &format!("{base}-shm"));
                }
            }
            _ => {
                let _ = write_error_frame(client, "unknown telegram op");
            }
        }
        unsafe { libc::close(client) };
    }
}

// ── Abstract socket creation ──────────────────────────────────────────────────

fn create_abstract_server(name: &str) -> Option<c_int> {
    // SAFETY: standard socket/bind/listen sequence
    let fd = unsafe { libc::socket(AF_UNIX, SOCK_STREAM, 0) };
    if fd < 0 {
        return None;
    }
    let mut addr: sockaddr_un = unsafe { std::mem::zeroed() };
    addr.sun_family = AF_UNIX as u16;
    // Abstract namespace: first byte stays \0, name goes after
    let name_bytes = name.as_bytes();
    let len = name_bytes.len().min(107);
    for (i, &b) in name_bytes[..len].iter().enumerate() {
        addr.sun_path[1 + i] = b as libc::c_char;
    }
    let addr_len = (std::mem::size_of::<libc::sa_family_t>() + 1 + len) as libc::socklen_t;
    let ret = unsafe { libc::bind(fd, &addr as *const _ as *const libc::sockaddr, addr_len) };
    if ret < 0 {
        unsafe { libc::close(fd) };
        return None;
    }
    unsafe { libc::listen(fd, 5) };
    Some(fd)
}

// ── Main companion handler ────────────────────────────────────────────────────

/// Entry point called from `zygisk_companion_entry`.
pub fn handle(sock: c_int) {
    let header = match read_header(sock) {
        Some(h) => h,
        None => {
            let _ = write_u8_to_fd(sock, COMPANION_ERROR);
            return;
        }
    };

    let enabled = is_enabled_target(header.uid, &header.process_name);

    match header.request_type {
        COMPANION_REQUEST_ENABLED => {
            let status = if enabled {
                COMPANION_ENABLED
            } else {
                COMPANION_DISABLED
            };
            let _ = write_u8_to_fd(sock, status);
        }
        COMPANION_REQUEST_TELEGRAM_SESSION => {
            if !enabled {
                let _ = write_u8_to_fd(sock, COMPANION_DISABLED);
                return;
            }
            let name = make_abstract_name(header.uid);
            let server_fd = match create_abstract_server(&name) {
                Some(fd) => fd,
                None => {
                    loge!("Zygisk: companion: failed to create abstract socket");
                    let _ = write_u8_to_fd(sock, COMPANION_ERROR);
                    return;
                }
            };
            // Double-fork: grandchild becomes a worker adopted by init
            // SAFETY: fork() is safe to call here; we exec nothing
            let mid_pid = unsafe { libc::fork() };
            if mid_pid < 0 {
                loge!("Zygisk: companion: fork failed");
                unsafe { libc::close(server_fd) };
                let _ = write_u8_to_fd(sock, COMPANION_ERROR);
                return;
            }
            if mid_pid == 0 {
                // Intermediate child: fork grandchild then exit immediately
                let grandchild = unsafe { libc::fork() };
                if grandchild == 0 {
                    // Grandchild: run the worker forever
                    telegram_worker(server_fd);
                    std::process::exit(0);
                }
                std::process::exit(0);
            }
            // Parent: wait for intermediate child to exit, then close our copy
            // of server_fd (grandchild has it; it stays alive under init)
            unsafe {
                let mut status = 0i32;
                libc::waitpid(mid_pid, &mut status, 0);
                libc::close(server_fd);
            }
            logi!("Zygisk: telegram socket ready: {name}");
            let _ = write_u8_to_fd(sock, COMPANION_ENABLED);
            let _ = write_string_to_fd(sock, &name);
        }
        _ => {
            let _ = write_error_frame(sock, "unknown request type");
        }
    }
}
