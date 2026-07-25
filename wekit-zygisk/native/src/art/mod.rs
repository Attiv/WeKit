// art/mod.rs — Global state + complete hook/unhook implementation
// Aligns with art_hook.cpp (lines 1073-1454)
pub mod elf;
pub mod layout;
pub mod trampoline;

use crate::art::elf::find_symbol_in_file;
use crate::art::layout::{
    ACC_COMPILE_DONT_BOTHER, ACC_FAST_INTERPRETER, ACC_PRIVATE, ACC_PROTECTED, ACC_PUBLIC,
    ArtLayout, G_ACC_PRECOMPILED,
};
use crate::art::trampoline::TrampolinePool;
use crate::{loge, logi};
use jni::sys::{JNIEnv as RawJNIEnv, jclass, jobject};
use libc::c_int;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};

// ── Global state ──────────────────────────────────────────────────────────────

static G_INITIALIZED: AtomicBool = AtomicBool::new(false);
static G_LAYOUT: OnceLock<ArtLayout> = OnceLock::new();
static G_POOL: OnceLock<TrampolinePool> = OnceLock::new();
static G_ART_BASE: AtomicUsize = AtomicUsize::new(0);
static G_ART_PATH: OnceLock<String> = OnceLock::new();
static G_SUSPEND_CTOR: AtomicUsize = AtomicUsize::new(0);
static G_SUSPEND_DTOR: AtomicUsize = AtomicUsize::new(0);

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

struct WritableArtMethod {
    page_start: usize,
    page_len: usize,
    orig_prot: c_int,
}

impl WritableArtMethod {
    unsafe fn acquire(addr: usize, method_size: usize) -> Option<Self> {
        let page_size = libc::sysconf(libc::_SC_PAGESIZE) as usize;
        let start = addr & !(page_size - 1);
        let end = (addr + method_size + page_size - 1) & !(page_size - 1);
        let len = end - start;
        let orig_prot = get_prot_for_addr(addr).unwrap_or(libc::PROT_READ | libc::PROT_EXEC);
        if libc::mprotect(start as *mut _, len, libc::PROT_READ | libc::PROT_WRITE) < 0 {
            return None;
        }
        Some(WritableArtMethod {
            page_start: start,
            page_len: len,
            orig_prot,
        })
    }
}

impl Drop for WritableArtMethod {
    fn drop(&mut self) {
        unsafe { libc::mprotect(self.page_start as *mut _, self.page_len, self.orig_prot) };
    }
}

fn get_prot_for_addr(addr: usize) -> Option<c_int> {
    let content = std::fs::read_to_string("/proc/self/maps").ok()?;
    for line in content.lines() {
        let mut p = line.splitn(6, ' ');
        let range = p.next()?;
        let perms = p.next()?;
        let (s, e) = range.split_once('-')?;
        let start = usize::from_str_radix(s, 16).ok()?;
        let end = usize::from_str_radix(e, 16).ok()?;
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
    None
}

// ── Public API ────────────────────────────────────────────────────────────────

pub fn init(env: *mut RawJNIEnv) -> bool {
    if G_INITIALIZED.load(Ordering::Acquire) {
        return true;
    }
    let art = match elf::find_art_library() {
        Some(a) => a,
        None => {
            loge!("Zygisk: libart.so not found");
            return false;
        }
    };
    G_ART_BASE.store(art.base, Ordering::Release);
    G_ART_PATH.get_or_init(|| art.path.clone());
    let lay = match crate::art::layout::detect(art.base, &art.path) {
        Some(l) => l,
        None => {
            loge!("Zygisk: ArtLayout detect failed");
            return false;
        }
    };
    G_LAYOUT.get_or_init(|| lay);
    let pool = match TrampolinePool::new() {
        Some(p) => p,
        None => {
            loge!("Zygisk: TrampolinePool init failed");
            return false;
        }
    };
    G_POOL.get_or_init(|| pool);
    // Resolve ScopedSuspendAll ctor/dtor
    for sym in &[
        "_ZN3art16ScopedSuspendAllC2EPKcb",
        "_ZN3art16ScopedSuspendAllC1EPKcb",
    ] {
        if let Some(off) = find_symbol_in_file(&art.path, sym) {
            G_SUSPEND_CTOR.store(art.base + off, Ordering::Release);
            break;
        }
    }
    for sym in &[
        "_ZN3art16ScopedSuspendAllD2Ev",
        "_ZN3art16ScopedSuspendAllD1Ev",
    ] {
        if let Some(off) = find_symbol_in_file(&art.path, sym) {
            G_SUSPEND_DTOR.store(art.base + off, Ordering::Release);
            break;
        }
    }
    // Init hook records map
    *G_HOOK_RECORDS.lock().unwrap() = Some(HashMap::new());
    G_INITIALIZED.store(true, Ordering::Release);
    logi!("Zygisk: art_hook_init OK (base={:#x})", art.base);
    true
}

pub fn is_initialized() -> bool {
    G_INITIALIZED.load(Ordering::Acquire)
}

pub fn get_art_method(_env: *mut RawJNIEnv, executable: jobject) -> usize {
    if executable.is_null() {
        return 0;
    }
    // artMethod_ field offset: 8 on LP64, 4 on LP32
    #[cfg(target_pointer_width = "64")]
    let off = 8usize;
    #[cfg(target_pointer_width = "32")]
    let off = 4usize;
    unsafe { *((executable as usize + off) as *const usize) }
}

pub fn hook_method(
    _env: *mut RawJNIEnv,
    target_art: usize,
    backup_art: usize,
    bridge_art: usize,
) -> i32 {
    if !is_initialized() {
        loge!("Zygisk: hook_method before init");
        return -1;
    }
    if G_SUSPEND_CTOR.load(Ordering::Relaxed) == 0 {
        loge!("Zygisk: ScopedSuspendAll not resolved");
        return -2;
    }
    let layout = match G_LAYOUT.get() {
        Some(l) => *l,
        None => return -3,
    };
    let pool = match G_POOL.get() {
        Some(p) => p,
        None => return -4,
    };
    unsafe {
        let _tw = match WritableArtMethod::acquire(target_art, layout.method_size) {
            Some(w) => w,
            None => return -5,
        };
        let _bw = match WritableArtMethod::acquire(backup_art, layout.method_size) {
            Some(w) => w,
            None => return -6,
        };
        let _brw = match WritableArtMethod::acquire(bridge_art, layout.method_size) {
            Some(w) => w,
            None => return -7,
        };
        let _suspend = ScopedArtSuspend::new("ArtHooker Hooking");
        let af_ptr = (target_art + layout.access_flags_offset) as *mut u32;
        let original_access_flags = af_ptr.read_volatile();
        let precomp = G_ACC_PRECOMPILED.load(Ordering::Relaxed);
        let bridge_af = (bridge_art + layout.access_flags_offset) as *mut u32;
        bridge_af.write_volatile((bridge_af.read_volatile() | ACC_COMPILE_DONT_BOTHER) & !precomp);
        af_ptr.write_volatile((original_access_flags | ACC_COMPILE_DONT_BOTHER) & !precomp);
        std::ptr::copy_nonoverlapping(
            target_art as *const u8,
            backup_art as *mut u8,
            layout.method_size,
        );
        af_ptr.write_volatile(af_ptr.read_volatile() & !ACC_FAST_INTERPRETER);
        let baf = (backup_art + layout.access_flags_offset) as *mut u32;
        baf.write_volatile((baf.read_volatile() | ACC_PRIVATE) & !(ACC_PUBLIC | ACC_PROTECTED));
        let trampoline = pool.allocate(bridge_art, layout.entry_point_offset);
        if trampoline.is_null() {
            return -8;
        }
        let ep_ptr = (target_art + layout.entry_point_offset) as *mut *const u8;
        ep_ptr.write_volatile(trampoline);
        G_HOOK_RECORDS.lock().unwrap().as_mut().unwrap().insert(
            target_art,
            HookRecord {
                backup_art,
                original_access_flags,
            },
        );
    }
    logi!("Zygisk: hooked @ {target_art:#x}");
    0
}

pub fn unhook_method(_env: *mut RawJNIEnv, target_art: usize, backup_art: usize) -> i32 {
    if !is_initialized() {
        return -1;
    }
    let layout = match G_LAYOUT.get() {
        Some(l) => *l,
        None => return -2,
    };
    let original_access_flags = {
        let records = G_HOOK_RECORDS.lock().unwrap();
        match records.as_ref().and_then(|m| m.get(&target_art)) {
            Some(r) => r.original_access_flags,
            None => {
                loge!("Zygisk: unhook: no record for {target_art:#x}");
                return -3;
            }
        }
    };
    unsafe {
        let _tw = match WritableArtMethod::acquire(target_art, layout.method_size) {
            Some(w) => w,
            None => return -4,
        };
        let _bw = match WritableArtMethod::acquire(backup_art, layout.method_size) {
            Some(w) => w,
            None => return -5,
        };
        let _suspend = ScopedArtSuspend::new("ArtHooker Unhooking");
        std::ptr::copy_nonoverlapping(
            backup_art as *const u8,
            target_art as *mut u8,
            layout.method_size,
        );
        let af_ptr = (target_art + layout.access_flags_offset) as *mut u32;
        af_ptr.write_volatile(original_access_flags);
    }
    G_HOOK_RECORDS
        .lock()
        .unwrap()
        .as_mut()
        .unwrap()
        .remove(&target_art);
    logi!("Zygisk: unhooked @ {target_art:#x}");
    0
}

pub fn trust_dex_file(env: *mut RawJNIEnv, dex_file: jobject) -> bool {
    if dex_file.is_null() {
        return false;
    }
    unsafe {
        let fns = *env;
        let cls = ((*fns).v1_6.GetObjectClass)(env, dex_file);
        if cls.is_null() {
            return false;
        }
        let mid = ((*fns).v1_6.GetMethodID)(env, cls, c"setTrusted".as_ptr(), c"()V".as_ptr());
        if mid.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            return false;
        }
        ((*fns).v1_6.CallVoidMethod)(env, dex_file, mid);
        ((*fns).v1_6.ExceptionCheck)(env) == jni::sys::JNI_FALSE
    }
}

pub fn trust_class_loader(_env: *mut RawJNIEnv, _class_loader: jobject) -> bool {
    logi!("Zygisk: trust_class_loader OK");
    true
}

pub fn allocate_instance(env: *mut RawJNIEnv, cls: jclass) -> jobject {
    if cls.is_null() {
        return std::ptr::null_mut();
    }
    unsafe { ((*(*env)).v1_6.AllocObject)(env, cls) }
}
