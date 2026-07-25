// ─────────────────────────────────────────────────────────────────────────────
// payload — APK/DEX file copy + InMemoryDexClassLoader construction
// ─────────────────────────────────────────────────────────────────────────────

use crate::loge;
use jni::sys::{JNIEnv as RawJNIEnv, jobject};
use libc::{gid_t, uid_t};
use std::{ffi::CString, fs, os::unix::io::RawFd};

// ── Directory creation ────────────────────────────────────────────────────────

pub fn ensure_dir(path: &str, uid: uid_t, gid: gid_t, mode: u32) -> bool {
    if let Err(e) = fs::create_dir_all(path) {
        loge!("Zygisk: ensure_dir {path}: {e}");
        return false;
    }
    let cpath = match CString::new(path) {
        Ok(s) => s,
        Err(_) => return false,
    };
    // SAFETY: standard POSIX chmod/chown on a path we just created
    unsafe {
        libc::chmod(cpath.as_ptr(), mode as libc::mode_t);
        libc::chown(cpath.as_ptr(), uid, gid);
    }
    true
}

// ── File copy ─────────────────────────────────────────────────────────────────

/// Copy a file from the module dir (by relative path) to dst_path.
/// Uses a temp file + atomic rename + fchown + fsync.
pub fn copy_module_file(
    module_dir_fd: RawFd,
    src_rel: &str,
    dst_path: &str,
    uid: uid_t,
    gid: gid_t,
    max_bytes: u64,
) -> bool {
    let src_cstr = match CString::new(src_rel) {
        Ok(s) => s,
        Err(_) => return false,
    };
    // SAFETY: openat on a trusted fd with a path we control
    let src_fd = unsafe {
        libc::openat(
            module_dir_fd,
            src_cstr.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC,
        )
    };
    if src_fd < 0 {
        loge!(
            "Zygisk: openat {src_rel}: {}",
            std::io::Error::last_os_error()
        );
        return false;
    }

    let tmp_path = format!("{dst_path}.tmp");
    let tmp_cstr = match CString::new(tmp_path.as_str()) {
        Ok(s) => s,
        Err(_) => {
            unsafe { libc::close(src_fd) };
            return false;
        }
    };
    let dst_cstr = match CString::new(dst_path) {
        Ok(s) => s,
        Err(_) => {
            unsafe { libc::close(src_fd) };
            return false;
        }
    };

    // SAFETY: creating a temp file for atomic rename
    let dst_fd = unsafe {
        libc::open(
            tmp_cstr.as_ptr(),
            libc::O_WRONLY | libc::O_CREAT | libc::O_TRUNC | libc::O_CLOEXEC,
            0o600,
        )
    };
    if dst_fd < 0 {
        unsafe { libc::close(src_fd) };
        return false;
    }

    let mut buf = [0u8; 65536];
    let mut total = 0u64;
    let mut ok = true;
    loop {
        let n = unsafe { libc::read(src_fd, buf.as_mut_ptr().cast(), buf.len()) };
        if n == 0 {
            break;
        }
        if n < 0 {
            ok = false;
            break;
        }
        total += n as u64;
        if total > max_bytes {
            loge!("Zygisk: {src_rel} exceeds {max_bytes} byte limit");
            ok = false;
            break;
        }
        let mut written = 0isize;
        while written < n {
            let w = unsafe {
                libc::write(
                    dst_fd,
                    buf[written as usize..].as_ptr().cast(),
                    (n - written) as usize,
                )
            };
            if w <= 0 {
                ok = false;
                break;
            }
            written += w;
        }
        if !ok {
            break;
        }
    }

    // SAFETY: fchown/fsync/close/rename on our own fds/paths
    unsafe {
        libc::fchown(dst_fd, uid, gid);
        if ok {
            libc::fsync(dst_fd);
        }
        libc::close(src_fd);
        libc::close(dst_fd);
        if ok {
            libc::rename(tmp_cstr.as_ptr(), dst_cstr.as_ptr());
        } else {
            libc::unlink(tmp_cstr.as_ptr());
        }
    }
    ok
}

/// Read a previously-copied file into memory.
pub fn read_file(path: &str) -> Option<Vec<u8>> {
    fs::read(path).ok()
}

// ── InMemoryDexClassLoader ────────────────────────────────────────────────────

/// Build an InMemoryDexClassLoader from byte slices, via raw JNI.
/// Returns a GlobalRef jobject or null on failure.
///
/// # Safety
///
/// `env` must be a valid JNIEnv pointer from the current thread.
/// `parent_loader` must be a valid local or global ref, or null.
pub unsafe fn build_dex_classloader(
    env: *mut RawJNIEnv,
    dex_buffers: &[Vec<u8>],
    parent_loader: jobject,
) -> jobject {
    let fns = *env;

    // --- Find ByteBuffer class ---
    let bb_class = ((*fns).v1_6.FindClass)(env, c"java/nio/ByteBuffer".as_ptr());
    if bb_class.is_null() {
        loge!("Zygisk: FindClass ByteBuffer failed");
        return std::ptr::null_mut();
    }

    // --- Allocate ByteBuffer[] ---
    let arr = ((*fns).v1_6.NewObjectArray)(
        env,
        dex_buffers.len() as i32,
        bb_class,
        std::ptr::null_mut(),
    );
    if arr.is_null() {
        loge!("Zygisk: NewObjectArray for ByteBuffer[] failed");
        ((*fns).v1_6.DeleteLocalRef)(env, bb_class);
        return std::ptr::null_mut();
    }

    for (i, buf) in dex_buffers.iter().enumerate() {
        let bb = ((*fns).v1_6.NewDirectByteBuffer)(env, buf.as_ptr() as *mut _, buf.len() as i64);
        if !bb.is_null() {
            ((*fns).v1_6.SetObjectArrayElement)(env, arr, i as i32, bb);
            ((*fns).v1_6.DeleteLocalRef)(env, bb);
        }
    }

    // --- Find InMemoryDexClassLoader ---
    let cl_class = ((*fns).v1_6.FindClass)(env, c"dalvik/system/InMemoryDexClassLoader".as_ptr());
    if cl_class.is_null() {
        loge!("Zygisk: FindClass InMemoryDexClassLoader failed");
        ((*fns).v1_6.DeleteLocalRef)(env, arr);
        ((*fns).v1_6.DeleteLocalRef)(env, bb_class);
        return std::ptr::null_mut();
    }

    let ctor = ((*fns).v1_6.GetMethodID)(
        env,
        cl_class,
        c"<init>".as_ptr(),
        c"([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V".as_ptr(),
    );
    if ctor.is_null() {
        loge!("Zygisk: GetMethodID InMemoryDexClassLoader.<init> failed");
        ((*fns).v1_6.DeleteLocalRef)(env, cl_class);
        ((*fns).v1_6.DeleteLocalRef)(env, arr);
        ((*fns).v1_6.DeleteLocalRef)(env, bb_class);
        return std::ptr::null_mut();
    }

    let loader = ((*fns).v1_6.NewObject)(env, cl_class, ctor, arr, parent_loader);

    ((*fns).v1_6.DeleteLocalRef)(env, cl_class);
    ((*fns).v1_6.DeleteLocalRef)(env, arr);
    ((*fns).v1_6.DeleteLocalRef)(env, bb_class);

    if loader.is_null() {
        loge!("Zygisk: InMemoryDexClassLoader construction failed");
        return std::ptr::null_mut();
    }

    ((*fns).v1_6.NewGlobalRef)(env, loader)
}
