// payload — APK/DEX 文件复制 + InMemoryDexClassLoader 构建

use crate::loge;
use jni::sys::{JNIEnv as RawJNIEnv, jobject};
use libc::{gid_t, uid_t};
use std::{ffi::CString, os::unix::io::RawFd};

// ── 目录创建 ──────────────────────────────────────────────────────────────────

/// 创建目录并设置权限，对应 C++ ensure_directory(path, 0700)。
pub fn ensure_dir(path: &str, uid: uid_t, gid: gid_t) -> bool {
    let cpath = match CString::new(path) {
        Ok(s) => s,
        Err(_) => return false,
    };
    unsafe {
        libc::mkdir(cpath.as_ptr(), 0o700); // 已存在时忽略错误
        libc::chmod(cpath.as_ptr(), 0o700 as libc::mode_t);
        if libc::geteuid() == 0 {
            libc::chown(cpath.as_ptr(), uid, gid);
        }
    }
    // 验证目录存在
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    unsafe {
        libc::stat(cpath.as_ptr(), &mut st) == 0 && (st.st_mode & libc::S_IFMT) == libc::S_IFDIR
    }
}

// ── 文件复制 ──────────────────────────────────────────────────────────────────

/// 从模块目录（通过 fd）复制文件到 dst_path。
/// 使用 PID 唯一临时文件 + atomic rename + fchown + fsync。
/// 对应 C++ copy_module_file。
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
    // O_NOFOLLOW 防止符号链接攻击
    let src_fd = unsafe {
        libc::openat(
            module_dir_fd,
            src_cstr.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    if src_fd < 0 {
        loge!(
            "Zygisk: openat {src_rel}: {}",
            std::io::Error::last_os_error()
        );
        return false;
    }
    // 验证源文件是普通文件且不超过 size 限制
    let mut src_stat: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::fstat(src_fd, &mut src_stat) } != 0
        || (src_stat.st_mode & libc::S_IFMT) != libc::S_IFREG
        || src_stat.st_size <= 0
        || src_stat.st_size as u64 > max_bytes
    {
        loge!("Zygisk: invalid module payload {src_rel}");
        unsafe { libc::close(src_fd) };
        return false;
    }

    // 使用 PID 区分临时文件，对应 C++ destination + "." + getpid() + ".tmp"
    let tmp_path = format!("{}.{}.tmp", dst_path, unsafe { libc::getpid() });
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
    // 先 unlink 旧临时文件（如果存在）
    unsafe {
        libc::unlink(tmp_cstr.as_ptr());
    }

    // O_CREAT | O_EXCL | O_NOFOLLOW — 安全创建，防覆盖/链接
    let dst_fd = unsafe {
        libc::open(
            tmp_cstr.as_ptr(),
            libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            0o600,
        )
    };
    if dst_fd < 0 {
        loge!(
            "Zygisk: cannot create {}: {}",
            tmp_path,
            std::io::Error::last_os_error()
        );
        unsafe { libc::close(src_fd) };
        return false;
    }

    let mut buf = [0u8; 65536];
    let mut ok = true;
    'copy: loop {
        let n = loop {
            let r = unsafe { libc::read(src_fd, buf.as_mut_ptr().cast(), buf.len()) };
            if r < 0 && std::io::Error::last_os_error().raw_os_error().unwrap_or(0) == libc::EINTR {
                continue;
            }
            break r;
        };
        if n == 0 {
            break;
        }
        if n < 0 {
            ok = false;
            break;
        }
        let mut written = 0usize;
        while written < n as usize {
            let w = loop {
                let r = unsafe {
                    libc::write(dst_fd, buf[written..].as_ptr().cast(), n as usize - written)
                };
                if r < 0
                    && std::io::Error::last_os_error().raw_os_error().unwrap_or(0) == libc::EINTR
                {
                    continue;
                }
                break r;
            };
            if w <= 0 {
                ok = false;
                break 'copy;
            }
            written += w as usize;
        }
    }

    if ok && unsafe { libc::geteuid() } == 0 && unsafe { libc::fchown(dst_fd, uid, gid) } != 0 {
        ok = false;
    }
    if ok && unsafe { libc::fsync(dst_fd) } != 0 {
        ok = false;
    }
    unsafe {
        libc::close(src_fd);
        libc::close(dst_fd);
    }

    if !ok || unsafe { libc::rename(tmp_cstr.as_ptr(), dst_cstr.as_ptr()) } != 0 {
        loge!(
            "Zygisk: failed to publish {}: {}",
            dst_path,
            std::io::Error::last_os_error()
        );
        unsafe {
            libc::unlink(tmp_cstr.as_ptr());
        }
        return false;
    }
    true
}

/// 读取已复制文件到内存（O_NOFOLLOW 防链接攻击）。
pub fn read_file(path: &str) -> Option<Vec<u8>> {
    let cpath = CString::new(path).ok()?;
    let fd = unsafe {
        libc::open(
            cpath.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    if fd < 0 {
        return None;
    }
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::fstat(fd, &mut st) } != 0
        || (st.st_mode & libc::S_IFMT) != libc::S_IFREG
        || st.st_size <= 0
    {
        unsafe { libc::close(fd) };
        return None;
    }
    let size = st.st_size as usize;
    let mut buf = vec![0u8; size];
    let mut got = 0usize;
    while got < size {
        let n = unsafe { libc::read(fd, buf[got..].as_mut_ptr().cast(), size - got) };
        if n <= 0 {
            unsafe { libc::close(fd) };
            return None;
        }
        got += n as usize;
    }
    unsafe { libc::close(fd) };
    Some(buf)
}

// ── InMemoryDexClassLoader ────────────────────────────────────────────────────

/// 从字节切片列表构建 InMemoryDexClassLoader（通过 raw JNI）。
///
/// # Safety
///
/// `env` 必须是当前线程的有效 JNIEnv 指针。
pub unsafe fn build_dex_classloader(
    env: *mut RawJNIEnv,
    dex_buffers: &[Vec<u8>],
    parent_loader: jobject,
) -> jobject {
    let fns = *env;
    let bb_class = ((*fns).v1_6.FindClass)(env, c"java/nio/ByteBuffer".as_ptr());
    if bb_class.is_null() {
        loge!("Zygisk: FindClass ByteBuffer failed");
        return std::ptr::null_mut();
    }
    let arr = ((*fns).v1_6.NewObjectArray)(
        env,
        dex_buffers.len() as i32,
        bb_class,
        std::ptr::null_mut(),
    );
    if arr.is_null() {
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
