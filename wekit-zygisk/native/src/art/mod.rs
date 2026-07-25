// art/mod.rs — ART 方法 hook 引擎
//
// 初始化流程：
//   1. 通过 dl_iterate_phdr 定位 libart.so
//   2. 通过 JNI 反射探测 ArtMethod 结构体大小和字段偏移
//   3. 解析 libart.so ELF，获取 ScopedSuspendAll/DexFile_setTrusted 等符号
//   4. 初始化双映射 trampoline pool
//
// hook_method 在持有 ScopedSuspendAll 锁的情况下原子性地安装 trampoline；
// unhook_method 从 backup 还原原始字节和 access_flags。

pub mod elf;
pub mod layout;
pub mod trampoline;

use crate::art::elf::find_symbol_in_file;
use crate::art::trampoline::TrampolinePool;
use crate::{loge, logi, logw};
use jni::sys::{JNI_FALSE, JNIEnv as RawJNIEnv, jclass, jfieldID, jmethodID, jobject};
use libc::c_int;
use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};

// ── acc_flags 常量（与 ART 源码一致）────────────────────────────────────────

pub const ACC_PUBLIC: u32 = 0x0001;
pub const ACC_PRIVATE: u32 = 0x0002;
pub const ACC_PROTECTED: u32 = 0x0004;
pub const ACC_STATIC: u32 = 0x0008;
pub const ACC_COMPILE_DONT_BOTHER: u32 = 0x02000000;
pub const ACC_FAST_INTERPRETER: u32 = 0x40000000; // kAccFastInterpreterToInterpreterInvoke
pub const ACC_INTRINSIC: u32 = 0x80000000;

// ── 全局状态 ─────────────────────────────────────────────────────────────────

static G_INITIALIZED: AtomicBool = AtomicBool::new(false);

// ArtMethod 布局（由 JNI 探测确定）
static G_ART_METHOD_SIZE: AtomicUsize = AtomicUsize::new(0);
static G_ENTRY_POINT_OFFSET: AtomicUsize = AtomicUsize::new(0);
static G_ACCESS_FLAGS_OFFSET: AtomicUsize = AtomicUsize::new(0);

// acc_precompiled 值随 API 等级变化：<30 → 0, 30 → 0x00200000, ≥31 → 0x00800000
static G_ACC_PRECOMPILED: AtomicUsize = AtomicUsize::new(0);
// kAccFastInterpreterToInterpreterInvoke：API<29 为 0，否则为 0x40000000
static G_ACC_FAST_INTERPRETER: AtomicUsize = AtomicUsize::new(0);

// JNI 字段 ID（存为 usize，因为 jfieldID = *mut _jfieldID 是裸指针）
static G_ART_METHOD_FIELD: AtomicUsize = AtomicUsize::new(0); // Executable.artMethod J
static G_ACCESS_FLAGS_FIELD: AtomicUsize = AtomicUsize::new(0); // Executable.accessFlags I

// 函数指针
static G_SUSPEND_CTOR: AtomicUsize = AtomicUsize::new(0); // ScopedSuspendAll ctor
static G_SUSPEND_DTOR: AtomicUsize = AtomicUsize::new(0); // ScopedSuspendAll dtor
static G_SET_NOT_INTRINSIC: AtomicUsize = AtomicUsize::new(0); // ArtMethod::SetNotIntrinsic
static G_SET_DEX_FILE_TRUSTED: AtomicUsize = AtomicUsize::new(0); // DexFile_setTrusted 符号
static G_SET_DEX_FILE_TRUSTED_METHOD: AtomicUsize = AtomicUsize::new(0); // jmethodID 兜底

// hook 记录表
struct HookRecord {
    backup_art: usize,
    original_access_flags: u32,
}
static G_HOOK_RECORDS: Mutex<Option<HashMap<usize, HookRecord>>> = Mutex::new(None);

// ── ScopedArtSuspend RAII ─────────────────────────────────────────────────────

struct ScopedArtSuspend {
    storage: [u8; 256],
}

impl ScopedArtSuspend {
    unsafe fn new(reason: &str) -> Self {
        let mut s = Self {
            storage: [0u8; 256],
        };
        let ctor_fn = G_SUSPEND_CTOR.load(Ordering::Acquire);
        if ctor_fn != 0 {
            let reason_c = std::ffi::CString::new(reason).unwrap_or_default();
            let ctor: unsafe extern "C" fn(*mut u8, *const u8, bool) = std::mem::transmute(ctor_fn);
            ctor(
                s.storage.as_mut_ptr(),
                reason_c.as_ptr() as *const u8,
                false,
            );
        }
        s
    }
    fn active(&self) -> bool {
        G_SUSPEND_CTOR.load(Ordering::Relaxed) != 0 && G_SUSPEND_DTOR.load(Ordering::Relaxed) != 0
    }
}

impl Drop for ScopedArtSuspend {
    fn drop(&mut self) {
        let dtor_fn = G_SUSPEND_DTOR.load(Ordering::Acquire);
        if dtor_fn != 0 {
            unsafe {
                let dtor: unsafe extern "C" fn(*mut u8) = std::mem::transmute(dtor_fn);
                dtor(self.storage.as_mut_ptr());
            }
        }
    }
}

// ── WritableArtMethod RAII ────────────────────────────────────────────────────
// 逐页跟踪并恢复保护，对应 C++ WritableArtMethod 的 pages_ 数组。

const MAX_PAGES: usize = 256 + 1; // kMaxArtMethodSize/page_size + 1

struct PageState {
    address: usize,
    length: usize,
    original_protection: c_int,
    changed: bool,
}

struct WritableArtMethod {
    pages: [PageState; MAX_PAGES],
    page_count: usize,
}

impl WritableArtMethod {
    fn new() -> Self {
        Self {
            pages: std::array::from_fn(|_| PageState {
                address: 0,
                length: 0,
                original_protection: 0,
                changed: false,
            }),
            page_count: 0,
        }
    }

    unsafe fn acquire(&mut self, address: usize, length: usize) -> bool {
        if address == 0 || length == 0 {
            return false;
        }
        let page_size = libc::sysconf(libc::_SC_PAGESIZE) as usize;
        if page_size == 0 {
            return false;
        }
        let first_page = address - address % page_size;
        let last_address = address + length - 1;
        let last_page = last_address - last_address % page_size;
        let page_count = (last_page - first_page) / page_size + 1;
        if page_count > MAX_PAGES {
            loge!(
                "Zygisk: ArtMethod page span unexpectedly large: {}",
                page_count
            );
            return false;
        }
        self.page_count = 0;
        let mut page = first_page;
        loop {
            let orig_prot = match get_prot_for_addr(page) {
                Some(p) => p,
                None => {
                    loge!("Zygisk: could not read mapping protection for {:#x}", page);
                    self.restore();
                    return false;
                }
            };
            let idx = self.page_count;
            self.pages[idx] = PageState {
                address: page,
                length: page_size,
                original_protection: orig_prot,
                changed: false,
            };
            self.page_count += 1;
            if orig_prot & libc::PROT_WRITE == 0 {
                if libc::mprotect(page as *mut _, page_size, orig_prot | libc::PROT_WRITE) != 0 {
                    loge!(
                        "Zygisk: mprotect writable failed at {:#x}: {}",
                        page,
                        std::io::Error::last_os_error()
                    );
                    self.restore();
                    return false;
                }
                self.pages[idx].changed = true;
            }
            if page == last_page {
                break;
            }
            page += page_size;
        }
        true
    }

    unsafe fn restore(&mut self) {
        for i in (0..self.page_count).rev() {
            let p = &mut self.pages[i];
            if p.changed {
                if libc::mprotect(p.address as *mut _, p.length, p.original_protection) != 0 {
                    loge!(
                        "Zygisk: mprotect restore failed at {:#x}: {}",
                        p.address,
                        std::io::Error::last_os_error()
                    );
                }
                p.changed = false;
            }
        }
    }
}

impl Drop for WritableArtMethod {
    fn drop(&mut self) {
        unsafe {
            self.restore();
        }
    }
}

fn get_prot_for_addr(addr: usize) -> Option<c_int> {
    let fd = unsafe { libc::open(c"/proc/self/maps".as_ptr(), libc::O_RDONLY) };
    if fd < 0 {
        return None;
    }
    let mut bytes = Vec::with_capacity(65536);
    let mut buf = [0u8; 4096];
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
        if n <= 0 {
            break;
        }
        bytes.extend_from_slice(&buf[..n as usize]);
    }
    unsafe {
        libc::close(fd);
    }
    for line in bytes.split(|&b| b == b'\n') {
        let s = std::str::from_utf8(line).unwrap_or("");
        let mut p = s.splitn(6, ' ');
        let range = p.next().unwrap_or("");
        let perms = p.next().unwrap_or("");
        if let Some((ss, es)) = range.split_once('-') {
            let start = usize::from_str_radix(ss, 16).unwrap_or(0);
            let end = usize::from_str_radix(es, 16).unwrap_or(0);
            if addr >= start && addr < end {
                let mut prot = 0i32;
                if perms.contains('r') {
                    prot |= libc::PROT_READ;
                }
                if perms.contains('w') {
                    prot |= libc::PROT_WRITE;
                }
                if perms.contains('x') {
                    prot |= libc::PROT_EXEC;
                }
                return Some(prot);
            }
        }
    }
    None
}

// ── JNI 初始化辅助 ─────────────────────────────────────────────────────────────

/// 找到 Executable.artMethod（J）和 Executable.accessFlags（I）字段 ID，
/// 对应 C++ 的 init_reflection_fields。
unsafe fn init_reflection_fields(env: *mut RawJNIEnv) -> bool {
    let fns = *env;
    let exec_cls = ((*fns).v1_6.FindClass)(env, c"java/lang/reflect/Executable".as_ptr());
    if exec_cls.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        loge!("Zygisk: Executable class not found");
        return false;
    }
    let art_method_fid =
        ((*fns).v1_6.GetFieldID)(env, exec_cls, c"artMethod".as_ptr(), c"J".as_ptr());
    if art_method_fid.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        ((*fns).v1_6.DeleteLocalRef)(env, exec_cls);
        loge!("Zygisk: Executable.artMethod field not found");
        return false;
    }
    let access_flags_fid =
        ((*fns).v1_6.GetFieldID)(env, exec_cls, c"accessFlags".as_ptr(), c"I".as_ptr());
    if access_flags_fid.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        ((*fns).v1_6.DeleteLocalRef)(env, exec_cls);
        loge!("Zygisk: Executable.accessFlags field not found");
        return false;
    }
    ((*fns).v1_6.DeleteLocalRef)(env, exec_cls);
    G_ART_METHOD_FIELD.store(art_method_fid as usize, Ordering::Release);
    G_ACCESS_FLAGS_FIELD.store(access_flags_fid as usize, Ordering::Release);
    true
}

/// 通过 Throwable.getDeclaredConstructors 获取两个相邻 ArtMethod* 的差值，
/// 以此测量 ArtMethod 结构体大小，对应 C++ 的 probe_art_method_layout。
unsafe fn probe_art_method_layout(env: *mut RawJNIEnv) -> Option<(usize, usize, u32)> // (first_art_method, method_size, access_flags)
{
    let fns = *env;
    let throwable = ((*fns).v1_6.FindClass)(env, c"java/lang/Throwable".as_ptr());
    let clazz = ((*fns).v1_6.FindClass)(env, c"java/lang/Class".as_ptr());
    if throwable.is_null() || clazz.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        if !throwable.is_null() {
            ((*fns).v1_6.DeleteLocalRef)(env, throwable);
        }
        if !clazz.is_null() {
            ((*fns).v1_6.DeleteLocalRef)(env, clazz);
        }
        return None;
    }
    let get_ctors = ((*fns).v1_6.GetMethodID)(
        env,
        clazz,
        c"getDeclaredConstructors".as_ptr(),
        c"()[Ljava/lang/reflect/Constructor;".as_ptr(),
    );
    ((*fns).v1_6.DeleteLocalRef)(env, clazz);
    if get_ctors.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        ((*fns).v1_6.DeleteLocalRef)(env, throwable);
        return None;
    }
    let ctors = ((*fns).v1_6.CallObjectMethod)(env, throwable, get_ctors);
    ((*fns).v1_6.DeleteLocalRef)(env, throwable);
    if ctors.is_null() || ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE {
        ((*fns).v1_6.ExceptionClear)(env);
        if !ctors.is_null() {
            ((*fns).v1_6.DeleteLocalRef)(env, ctors);
        }
        return None;
    }
    let ctors_arr = ctors as jni::sys::jobjectArray;
    if ((*fns).v1_6.GetArrayLength)(env, ctors_arr) < 2 {
        ((*fns).v1_6.DeleteLocalRef)(env, ctors);
        return None;
    }
    let c0 = ((*fns).v1_6.GetObjectArrayElement)(env, ctors_arr, 0);
    let c1 = ((*fns).v1_6.GetObjectArrayElement)(env, ctors_arr, 1);
    let art_fid = G_ART_METHOD_FIELD.load(Ordering::Relaxed) as jfieldID;
    let af_fid = G_ACCESS_FLAGS_FIELD.load(Ordering::Relaxed) as jfieldID;
    let first = if !c0.is_null() && !art_fid.is_null() {
        ((*fns).v1_6.GetLongField)(env, c0, art_fid) as usize
    } else {
        0
    };
    let second = if !c1.is_null() && !art_fid.is_null() {
        ((*fns).v1_6.GetLongField)(env, c1, art_fid) as usize
    } else {
        0
    };
    let flags = if !c0.is_null() && !af_fid.is_null() {
        ((*fns).v1_6.GetIntField)(env, c0, af_fid) as u32
    } else {
        0
    };
    if !c0.is_null() {
        ((*fns).v1_6.DeleteLocalRef)(env, c0);
    }
    if !c1.is_null() {
        ((*fns).v1_6.DeleteLocalRef)(env, c1);
    }
    ((*fns).v1_6.DeleteLocalRef)(env, ctors);
    ((*fns).v1_6.ExceptionClear)(env);
    if first == 0 || second == 0 {
        return None;
    }
    let size = first.abs_diff(second);
    // 合理范围检查
    if size < std::mem::size_of::<usize>() * 3
        || size > 256
        || size % std::mem::size_of::<usize>() != 0
    {
        loge!("Zygisk: ArtMethod probe: invalid size {}", size);
        return None;
    }
    Some((first, size, flags))
}

/// 在 ArtMethod 内存中扫描 access_flags 值，确定其偏移，
/// 对应 C++ 的 find_access_flags_offset。
unsafe fn find_access_flags_offset(
    art_method: usize,
    method_size: usize,
    reflected_flags: u32,
) -> Option<usize> {
    let mut found: Option<usize> = None;
    let mut candidate = 0usize;
    while candidate + 4 <= method_size {
        let value = ((art_method + candidate) as *const u32).read_unaligned();
        if value == reflected_flags {
            if candidate == 4 {
                // preferred position (after declaring_class GC root)
                return Some(candidate);
            }
            if found.is_none() {
                found = Some(candidate);
            }
        }
        candidate += 4;
    }
    found
}

/// 解析 DexFile.setTrusted jmethodID 作为 DexFile_setTrusted 符号的兜底。
unsafe fn resolve_dex_file_set_trusted_method(env: *mut RawJNIEnv) -> jmethodID {
    let fns = *env;
    let cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/DexFile".as_ptr());
    if cls.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    // setTrusted(Object cookie) 是 package-private 静态方法
    let mid = ((*fns).v1_6.GetStaticMethodID)(
        env,
        cls,
        c"setTrusted".as_ptr(),
        c"(Ljava/lang/Object;)V".as_ptr(),
    );
    if mid.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
    }
    ((*fns).v1_6.DeleteLocalRef)(env, cls);
    mid
}

fn has_dex_file_trust_backend() -> bool {
    G_SET_DEX_FILE_TRUSTED.load(Ordering::Relaxed) != 0
        || G_SET_DEX_FILE_TRUSTED_METHOD.load(Ordering::Relaxed) != 0
}

// ── 公开 API ──────────────────────────────────────────────────────────────────

/// 初始化 ART hook 引擎：JNI 探测布局、解析符号、分配 trampoline pool。
/// 对应 C++ art_hook_init。
pub fn init(env: *mut RawJNIEnv) -> bool {
    if G_INITIALIZED.load(Ordering::Acquire) {
        return true;
    }

    // 1. 定位 libart.so
    let art = match elf::find_art_library() {
        Some(a) => a,
        None => {
            loge!("Zygisk: libart.so not found");
            return false;
        }
    };

    // 2. 反射字段 ID
    if !unsafe { init_reflection_fields(env) } {
        loge!("Zygisk: failed to probe ART reflection fields");
        return false;
    }

    // 3. JNI 探测 ArtMethod 布局
    let (first_art_method, method_size, sample_flags) =
        match unsafe { probe_art_method_layout(env) } {
            Some(v) => v,
            None => {
                loge!("Zygisk: failed to probe ART method layout");
                return false;
            }
        };

    // 4. 扫描 access_flags 偏移
    let access_flags_offset =
        match unsafe { find_access_flags_offset(first_art_method, method_size, sample_flags) } {
            Some(o) => o,
            None => {
                loge!("Zygisk: failed to find access_flags offset");
                return false;
            }
        };

    // 5. entry_point 在 ArtMethod 末尾
    let entry_point_offset = method_size - std::mem::size_of::<usize>();
    if access_flags_offset + 4 > entry_point_offset {
        loge!(
            "Zygisk: invalid layout: flags={} entry={} size={}",
            access_flags_offset,
            entry_point_offset,
            method_size
        );
        return false;
    }

    // 6. 解析必需符号（ScopedSuspendAll ctor/dtor）
    let resolve = |sym: &str, prefix: bool| -> usize {
        elf::resolve_art_symbol(art.base, &art.path, sym, prefix)
    };
    let suspend_ctor = {
        let v = resolve("_ZN3art16ScopedSuspendAllC2EPKcb", false);
        if v != 0 {
            v
        } else {
            resolve("_ZN3art16ScopedSuspendAllC1EPKcb", false)
        }
    };
    let suspend_dtor = {
        let v = resolve("_ZN3art16ScopedSuspendAllD2Ev", false);
        if v != 0 {
            v
        } else {
            resolve("_ZN3art16ScopedSuspendAllD1Ev", false)
        }
    };

    // SetNotIntrinsic（可选）
    let set_not_intrinsic = resolve("_ZN3art9ArtMethod15SetNotIntrinsicEv", false);

    // DexFile_setTrusted 符号（可选，有 JNI 兜底）
    let mut set_trusted = resolve(
        "_ZN3artL18DexFile_setTrustedEP7_JNIEnvP7_jclassP8_jobject",
        true,
    );
    if set_trusted == 0 {
        // 尝试 .gnu_debugdata
        if let Some(off) = find_symbol_in_file(
            &art.path,
            "_ZN3artL18DexFile_setTrustedEP7_JNIEnvP7_jclassP8_jobject",
        ) {
            set_trusted = art.base + off;
            logi!("Zygisk: DexFile_setTrusted resolved from .gnu_debugdata");
        }
    }
    let set_trusted_method = if set_trusted == 0 {
        unsafe { resolve_dex_file_set_trusted_method(env) as usize }
    } else {
        0
    };

    if suspend_ctor == 0 || suspend_dtor == 0 || (set_trusted == 0 && set_trusted_method == 0) {
        loge!(
            "Zygisk: required ART entry missing: ctor={:#x} dtor={:#x} trusted_sym={:#x} trusted_mid={:#x}",
            suspend_ctor,
            suspend_dtor,
            set_trusted,
            set_trusted_method
        );
        return false;
    }

    // 7. acc 位掩码（按 API 等级）
    // android_get_device_api_level 在 libandroid.so 中
    unsafe extern "C" {
        fn android_get_device_api_level() -> libc::c_int;
    }
    let api = unsafe { android_get_device_api_level() };
    let acc_precompiled: u32 = if api < 30 {
        0
    } else if api >= 31 {
        0x00800000
    } else {
        0x00200000
    };
    let acc_fast_interpreter: u32 = if api < 29 { 0 } else { ACC_FAST_INTERPRETER };

    // 8. trampoline pool
    let pool = match TrampolinePool::new() {
        Some(p) => p,
        None => {
            loge!("Zygisk: TrampolinePool init failed");
            return false;
        }
    };

    // 提交全局状态
    G_ART_METHOD_SIZE.store(method_size, Ordering::Release);
    G_ENTRY_POINT_OFFSET.store(entry_point_offset, Ordering::Release);
    G_ACCESS_FLAGS_OFFSET.store(access_flags_offset, Ordering::Release);
    G_ACC_PRECOMPILED.store(acc_precompiled as usize, Ordering::Release);
    G_ACC_FAST_INTERPRETER.store(acc_fast_interpreter as usize, Ordering::Release);
    G_SUSPEND_CTOR.store(suspend_ctor, Ordering::Release);
    G_SUSPEND_DTOR.store(suspend_dtor, Ordering::Release);
    if set_not_intrinsic != 0 {
        G_SET_NOT_INTRINSIC.store(set_not_intrinsic, Ordering::Release);
    } else {
        logw!("Zygisk: ArtMethod::SetNotIntrinsic unavailable; using access-flag fallback");
    }
    if set_trusted != 0 {
        G_SET_DEX_FILE_TRUSTED.store(set_trusted, Ordering::Release);
    } else {
        G_SET_DEX_FILE_TRUSTED_METHOD.store(set_trusted_method, Ordering::Release);
        logw!("Zygisk: DexFile_setTrusted symbol unavailable; using registered JNI method");
    }
    *G_HOOK_RECORDS.lock().unwrap() = Some(HashMap::new());
    G_POOL_STORAGE.get_or_init(|| pool);
    G_INITIALIZED.store(true, Ordering::Release);

    logi!(
        "Zygisk: ART layout: method_size={} entry_offset={} access_flags_offset={} api={}",
        method_size,
        entry_point_offset,
        access_flags_offset,
        api
    );
    true
}

static G_POOL_STORAGE: OnceLock<TrampolinePool> = OnceLock::new();

pub fn is_initialized() -> bool {
    G_INITIALIZED.load(Ordering::Acquire)
}

/// 从 java.lang.reflect.Executable 获取对应的 ArtMethod*。
/// 优先使用 JNI GetLongField(artMethod)，兜底用 FromReflectedMethod。
pub fn get_art_method(env: *mut RawJNIEnv, executable: jobject) -> usize {
    if executable.is_null() {
        return 0;
    }
    unsafe {
        let fns = *env;
        let art_fid = G_ART_METHOD_FIELD.load(Ordering::Relaxed) as jfieldID;
        if !art_fid.is_null() {
            let val = ((*fns).v1_6.GetLongField)(env, executable, art_fid) as usize;
            if ((*fns).v1_6.ExceptionCheck)(env) == JNI_FALSE && val != 0 {
                return val;
            }
            ((*fns).v1_6.ExceptionClear)(env);
        }
        // 兜底：FromReflectedMethod
        ((*fns).v1_6.FromReflectedMethod)(env, executable) as usize
    }
}

/// 安装方法 hook。调用方负责确保 target/backup/bridge 是有效的 ArtMethod*。
/// 对应 C++ art_hook_method。
pub fn hook_method(
    _env: *mut RawJNIEnv,
    target_art: usize,
    backup_art: usize,
    bridge_art: usize,
) -> i32 {
    if !is_initialized() || target_art == 0 || backup_art == 0 || bridge_art == 0 {
        return -1;
    }

    let suspend = unsafe { ScopedArtSuspend::new("ArtHooker Hooking") };
    if !suspend.active() {
        loge!("Zygisk: ART mutation suspend guard unavailable");
        return -2;
    }

    let method_size = G_ART_METHOD_SIZE.load(Ordering::Relaxed);
    let af_off = G_ACCESS_FLAGS_OFFSET.load(Ordering::Relaxed);
    let ep_off = G_ENTRY_POINT_OFFSET.load(Ordering::Relaxed);
    let precomp = G_ACC_PRECOMPILED.load(Ordering::Relaxed) as u32;
    let fast_interp = G_ACC_FAST_INTERPRETER.load(Ordering::Relaxed) as u32;

    unsafe {
        // 检查是否已 hook
        if G_HOOK_RECORDS
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|m| m.contains_key(&target_art))
        {
            loge!(
                "Zygisk: target={:#x} already has an active hook",
                target_art
            );
            return -3;
        }

        let mut tw = WritableArtMethod::new();
        let mut bw = WritableArtMethod::new();
        let mut brw = WritableArtMethod::new();
        if !tw.acquire(target_art, method_size) {
            return -4;
        }
        if !bw.acquire(backup_art, method_size) {
            return -5;
        }
        if !brw.acquire(bridge_art, method_size) {
            return -6;
        }

        // 保存原始 access_flags（只有这一份是干净的）
        let original_access_flags = ((target_art + af_off) as *const u32).read_volatile();

        // 先设置 bridge：加 ACC_COMPILE_DONT_BOTHER，清 precompiled
        let bridge_af = (bridge_art + af_off) as *mut u32;
        bridge_af.write_volatile((bridge_af.read_volatile() | ACC_COMPILE_DONT_BOTHER) & !precomp);

        // 对 target：清 intrinsic，加 ACC_COMPILE_DONT_BOTHER，清 precompiled
        call_set_not_intrinsic(target_art);
        let target_af = (target_art + af_off) as *mut u32;
        target_af.write_volatile((original_access_flags | ACC_COMPILE_DONT_BOTHER) & !precomp);

        // 将 target 快照到 backup
        std::ptr::copy_nonoverlapping(target_art as *const u8, backup_art as *mut u8, method_size);

        // 清 target 的 fast_interpreter 位
        target_af.write_volatile(target_af.read_volatile() & !fast_interp);

        // backup 非静态方法改为 private
        let baf = (backup_art + af_off) as *mut u32;
        if baf.read_volatile() & ACC_STATIC == 0 {
            baf.write_volatile((baf.read_volatile() | ACC_PRIVATE) & !(ACC_PUBLIC | ACC_PROTECTED));
        }

        // 分配并写入 trampoline
        let pool = match G_POOL_STORAGE.get() {
            Some(p) => p,
            None => return -7,
        };
        let trampoline = pool.allocate(bridge_art, ep_off);
        if trampoline.is_null() {
            return -8;
        }
        let ep_ptr = (target_art + ep_off) as *mut *const u8;
        ep_ptr.write_volatile(trampoline);

        G_HOOK_RECORDS.lock().unwrap().as_mut().unwrap().insert(
            target_art,
            HookRecord {
                backup_art,
                original_access_flags,
            },
        );
    }
    logi!(
        "Zygisk: hooked target={:#x} bridge={:#x}",
        target_art,
        bridge_art
    );
    0
}

unsafe fn call_set_not_intrinsic(art_method: usize) {
    let fn_ptr = G_SET_NOT_INTRINSIC.load(Ordering::Relaxed);
    if fn_ptr != 0 {
        let f: unsafe extern "C" fn(*mut c_void) = std::mem::transmute(fn_ptr);
        f(art_method as *mut c_void);
    } else {
        // 兜底：手动清 ACC_INTRINSIC 位
        let af_off = G_ACCESS_FLAGS_OFFSET.load(Ordering::Relaxed);
        let af = (art_method + af_off) as *mut u32;
        af.write_volatile(af.read_volatile() & !ACC_INTRINSIC);
    }
}

/// 卸载方法 hook，从 backup 还原。对应 C++ art_unhook_method。
pub fn unhook_method(_env: *mut RawJNIEnv, target_art: usize, backup_art: usize) -> i32 {
    if !is_initialized() || target_art == 0 || backup_art == 0 {
        return -1;
    }

    let method_size = G_ART_METHOD_SIZE.load(Ordering::Relaxed);
    let af_off = G_ACCESS_FLAGS_OFFSET.load(Ordering::Relaxed);

    let original_access_flags = {
        let records = G_HOOK_RECORDS.lock().unwrap();
        match records.as_ref().and_then(|m| m.get(&target_art)) {
            Some(r) if r.backup_art == backup_art => r.original_access_flags,
            _ => {
                loge!(
                    "Zygisk: unhook: target={:#x} has no matching active hook",
                    target_art
                );
                return -2;
            }
        }
    };

    let suspend = unsafe { ScopedArtSuspend::new("ArtHooker Unhooking") };
    if !suspend.active() {
        return -3;
    }

    unsafe {
        let mut tw = WritableArtMethod::new();
        if !tw.acquire(target_art, method_size) {
            return -4;
        }
        std::ptr::copy_nonoverlapping(backup_art as *const u8, target_art as *mut u8, method_size);
        ((target_art + af_off) as *mut u32).write_volatile(original_access_flags);
    }
    G_HOOK_RECORDS
        .lock()
        .unwrap()
        .as_mut()
        .unwrap()
        .remove(&target_art);
    logi!("Zygisk: unhooked target={:#x}", target_art);
    0
}

/// 通过 mCookie + DexFile_setTrusted 符号（或注册的 JNI 方法兜底）信任 DexFile。
/// 对应 C++ art_trust_dex_file / trust_dex_file。
pub fn trust_dex_file(env: *mut RawJNIEnv, dex_file: jobject) -> bool {
    if !is_initialized() || env.is_null() || dex_file.is_null() || !has_dex_file_trust_backend() {
        return false;
    }
    unsafe {
        let fns = *env;
        // 找 dalvik.system.DexFile 类
        let dex_cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/DexFile".as_ptr());
        if dex_cls.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            loge!("Zygisk: DexFile class not found");
            return false;
        }
        // 获取 mCookie 字段
        let cookie_fid = ((*fns).v1_6.GetFieldID)(
            env,
            dex_cls,
            c"mCookie".as_ptr(),
            c"Ljava/lang/Object;".as_ptr(),
        );
        if cookie_fid.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            ((*fns).v1_6.DeleteLocalRef)(env, dex_cls);
            loge!("Zygisk: DexFile.mCookie not found");
            return false;
        }
        let cookie = ((*fns).v1_6.GetObjectField)(env, dex_file, cookie_fid);
        if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE || cookie.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            if !cookie.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, cookie);
            }
            ((*fns).v1_6.DeleteLocalRef)(env, dex_cls);
            loge!("Zygisk: DexFile.mCookie is null");
            return false;
        }
        // 调用 DexFile_setTrusted(env, class, cookie)
        let sym_fn = G_SET_DEX_FILE_TRUSTED.load(Ordering::Relaxed);
        if sym_fn != 0 {
            type SetTrustedFn = unsafe extern "C" fn(*mut RawJNIEnv, jclass, jobject);
            let f: SetTrustedFn = std::mem::transmute(sym_fn);
            f(env, dex_cls, cookie);
        } else {
            // JNI 方法兜底
            let mid = G_SET_DEX_FILE_TRUSTED_METHOD.load(Ordering::Relaxed) as jmethodID;
            ((*fns).v1_6.CallStaticVoidMethod)(env, dex_cls, mid, cookie);
        }
        let ok = ((*fns).v1_6.ExceptionCheck)(env) == JNI_FALSE;
        if !ok {
            loge!("Zygisk: DexFile.setTrusted exception");
            ((*fns).v1_6.ExceptionClear)(env);
        }
        ((*fns).v1_6.DeleteLocalRef)(env, cookie);
        ((*fns).v1_6.DeleteLocalRef)(env, dex_cls);
        ok
    }
}

/// 遍历 BaseDexClassLoader.pathList.dexElements[].dexFile 并逐一 trust。
/// 对应 C++ art_trust_class_loader。
pub fn trust_class_loader(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    if !is_initialized() || env.is_null() || class_loader.is_null() || !has_dex_file_trust_backend()
    {
        return false;
    }
    unsafe {
        let fns = *env;
        let base_cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/BaseDexClassLoader".as_ptr());
        let plist_cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/DexPathList".as_ptr());
        let elem_cls = ((*fns).v1_6.FindClass)(env, c"dalvik/system/DexPathList$Element".as_ptr());
        if base_cls.is_null() || plist_cls.is_null() || elem_cls.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            for p in [base_cls, plist_cls, elem_cls]
                .iter()
                .filter(|&&p| !p.is_null())
            {
                ((*fns).v1_6.DeleteLocalRef)(env, *p);
            }
            return false;
        }
        let plist_fid = ((*fns).v1_6.GetFieldID)(
            env,
            base_cls,
            c"pathList".as_ptr(),
            c"Ldalvik/system/DexPathList;".as_ptr(),
        );
        let elems_fid = ((*fns).v1_6.GetFieldID)(
            env,
            plist_cls,
            c"dexElements".as_ptr(),
            c"[Ldalvik/system/DexPathList$Element;".as_ptr(),
        );
        let dexfile_fid = ((*fns).v1_6.GetFieldID)(
            env,
            elem_cls,
            c"dexFile".as_ptr(),
            c"Ldalvik/system/DexFile;".as_ptr(),
        );
        if plist_fid.is_null() || elems_fid.is_null() || dexfile_fid.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            ((*fns).v1_6.DeleteLocalRef)(env, base_cls);
            ((*fns).v1_6.DeleteLocalRef)(env, plist_cls);
            ((*fns).v1_6.DeleteLocalRef)(env, elem_cls);
            loge!("Zygisk: could not resolve BaseDexClassLoader DexFile fields");
            return false;
        }
        let path_list = ((*fns).v1_6.GetObjectField)(env, class_loader, plist_fid);
        let elements = if !path_list.is_null() {
            ((*fns).v1_6.GetObjectField)(env, path_list, elems_fid) as jni::sys::jobjectArray
        } else {
            std::ptr::null_mut()
        };
        if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE || elements.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            if !path_list.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, path_list);
            }
            if !elements.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, elements as jobject);
            }
            for p in [base_cls, plist_cls, elem_cls] {
                ((*fns).v1_6.DeleteLocalRef)(env, p);
            }
            return false;
        }
        let count = ((*fns).v1_6.GetArrayLength)(env, elements);
        let mut success = true;
        let mut trusted_count = 0i32;
        for i in 0..count {
            let elem = ((*fns).v1_6.GetObjectArrayElement)(env, elements, i);
            let dex_file = if !elem.is_null() {
                ((*fns).v1_6.GetObjectField)(env, elem, dexfile_fid)
            } else {
                std::ptr::null_mut()
            };
            if ((*fns).v1_6.ExceptionCheck)(env) != JNI_FALSE {
                ((*fns).v1_6.ExceptionClear)(env);
                success = false;
            } else if !dex_file.is_null() {
                if trust_dex_file(env, dex_file) {
                    trusted_count += 1;
                } else {
                    success = false;
                }
            }
            if !dex_file.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, dex_file);
            }
            if !elem.is_null() {
                ((*fns).v1_6.DeleteLocalRef)(env, elem);
            }
        }
        ((*fns).v1_6.DeleteLocalRef)(env, elements as jobject);
        if !path_list.is_null() {
            ((*fns).v1_6.DeleteLocalRef)(env, path_list);
        }
        for p in [base_cls, plist_cls, elem_cls] {
            ((*fns).v1_6.DeleteLocalRef)(env, p);
        }
        if !success || trusted_count == 0 {
            loge!(
                "Zygisk: failed to trust every DexFile in class loader (trusted={})",
                trusted_count
            );
            return false;
        }
        logi!(
            "Zygisk: trusted {} DexFile(s) for class loader {:p}",
            trusted_count,
            class_loader
        );
        true
    }
}

pub fn allocate_instance(env: *mut RawJNIEnv, cls: jclass) -> jobject {
    if cls.is_null() {
        return std::ptr::null_mut();
    }
    unsafe { ((*(*env)).v1_6.AllocObject)(env, cls) }
}
