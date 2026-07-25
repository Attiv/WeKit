// jni.rs — RegisterNatives for ArtHookBridge (6 methods) + ZygiskEntry (4 methods)
// Aligns with main.cpp lines 1240-1312

use crate::{loge, logi};
use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNIEnv as RawJNIEnv, JNINativeMethod, jboolean, jclass, jint, jlong,
    jobject, jstring,
};
use std::ffi::{CString, c_char, c_void};

// ── JNI helper: load class via ClassLoader.loadClass ─────────────────────────

unsafe fn load_class_via_loader(env: *mut RawJNIEnv, loader: jobject, dot_name: &str) -> jclass {
    let fns = *env;
    let jname = CString::new(dot_name).unwrap_or_default();
    let jname_obj = ((*fns).v1_6.NewStringUTF)(env, jname.as_ptr());
    if jname_obj.is_null() {
        return std::ptr::null_mut();
    }
    let loader_cls = ((*fns).v1_6.GetObjectClass)(env, loader);
    let mid = ((*fns).v1_6.GetMethodID)(
        env,
        loader_cls,
        c"loadClass".as_ptr(),
        c"(Ljava/lang/String;)Ljava/lang/Class;".as_ptr(),
    );
    if mid.is_null() {
        ((*fns).v1_6.ExceptionClear)(env);
        return std::ptr::null_mut();
    }
    ((*fns).v1_6.CallObjectMethod)(env, loader, mid, jname_obj) as jclass
}

// ── ArtHookBridge JNI implementations ────────────────────────────────────────

extern "C" fn jni_get_art_method(
    env: *mut RawJNIEnv,
    _class: jclass,
    executable: jobject,
) -> jlong {
    crate::art::get_art_method(env, executable) as jlong
}

extern "C" fn jni_hook_method(
    env: *mut RawJNIEnv,
    _class: jclass,
    target_art: jlong,
    backup_art: jlong,
    bridge_art: jlong,
    _hook_id: jlong,
) -> jint {
    crate::art::hook_method(
        env,
        target_art as usize,
        backup_art as usize,
        bridge_art as usize,
    ) as jint
}

extern "C" fn jni_unhook_method(
    env: *mut RawJNIEnv,
    _class: jclass,
    target_art: jlong,
    backup_art: jlong,
) -> jint {
    crate::art::unhook_method(env, target_art as usize, backup_art as usize) as jint
}

extern "C" fn jni_trust_dex_file(
    env: *mut RawJNIEnv,
    _class: jclass,
    dex_file: jobject,
) -> jboolean {
    if crate::art::trust_dex_file(env, dex_file) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

extern "C" fn jni_allocate_instance(
    env: *mut RawJNIEnv,
    _class: jclass,
    target_class: jclass,
) -> jobject {
    crate::art::allocate_instance(env, target_class)
}

extern "C" fn jni_hide_loaded_module_libraries(_env: *mut RawJNIEnv, _class: jclass) -> jboolean {
    let ok = crate::so_hider::hide_path("libdexkit.so") >= 0
        && crate::so_hider::hide_path("libwekit_native.so") >= 0
        && crate::so_hider::hide_path("libmmkv.so") >= 0;
    if ok { JNI_TRUE } else { JNI_FALSE }
}

// ── ZygiskEntry JNI implementations ──────────────────────────────────────────

extern "C" fn jni_native_initialize(env: *mut RawJNIEnv, _class: jclass) -> jboolean {
    if crate::art::init(env) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

extern "C" fn jni_has_telegram_root_companion(_env: *mut RawJNIEnv, _class: jclass) -> jboolean {
    // TODO: connect telegram socket and send DISCOVER ping (Task 10)
    JNI_FALSE
}

extern "C" fn jni_list_telegram_instances(_env: *mut RawJNIEnv, _class: jclass) -> jobject {
    // TODO: connect telegram socket and return String[] (Task 10)
    std::ptr::null_mut()
}

extern "C" fn jni_copy_telegram_database_snapshot(
    _env: *mut RawJNIEnv,
    _class: jclass,
    _package_name: jstring,
    _database_fd: jint,
    _wal_fd: jint,
    _shm_fd: jint,
) -> jint {
    // TODO: connect telegram socket (Task 10)
    -1
}

// ── RegisterNatives ───────────────────────────────────────────────────────────

/// Register ArtHookBridge native methods.
/// Class must be loaded via class_loader (InMemoryDexClassLoader).
///
/// # Safety
///
/// `env` must be a valid JNIEnv pointer. `class_loader` must be a valid
/// reference to the InMemoryDexClassLoader holding ArtHookBridge.
pub unsafe fn register_hook_bridge_natives(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    let class = load_class_via_loader(
        env,
        class_loader,
        "dev.ujhhgtg.wekit.loader.entry.zygisk.ArtHookBridge",
    );
    if class.is_null() {
        loge!("Zygisk: failed to load ArtHookBridge class");
        return false;
    }

    let mut methods: [JNINativeMethod; 6] = [
        JNINativeMethod {
            name: c"nativeGetArtMethod".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/reflect/Executable;)J".as_ptr() as *mut c_char,
            fnPtr: jni_get_art_method as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeHookMethod".as_ptr() as *mut c_char,
            signature: c"(JJJJ)I".as_ptr() as *mut c_char,
            fnPtr: jni_hook_method as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeUnhookMethod".as_ptr() as *mut c_char,
            signature: c"(JJ)I".as_ptr() as *mut c_char,
            fnPtr: jni_unhook_method as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeTrustDexFile".as_ptr() as *mut c_char,
            signature: c"(Ldalvik/system/DexFile;)Z".as_ptr() as *mut c_char,
            fnPtr: jni_trust_dex_file as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeAllocateInstance".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/Class;)Ljava/lang/Object;".as_ptr() as *mut c_char,
            fnPtr: jni_allocate_instance as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeHideLoadedModuleLibraries".as_ptr() as *mut c_char,
            signature: c"()Z".as_ptr() as *mut c_char,
            fnPtr: jni_hide_loaded_module_libraries as *mut c_void,
        },
    ];

    let fns = *env;
    let ret = ((*fns).v1_6.RegisterNatives)(env, class, methods.as_mut_ptr(), 6);
    if ret == 0 {
        logi!("Zygisk: ArtHookBridge natives registered");
        true
    } else {
        loge!("Zygisk: RegisterNatives(ArtHookBridge) failed: {ret}");
        false
    }
}

/// Register ZygiskEntry native methods.
///
/// # Safety
///
/// `env` must be a valid JNIEnv pointer. `class_loader` must be a valid
/// reference to the InMemoryDexClassLoader holding ZygiskEntry.
pub unsafe fn register_entry_natives(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    let class = load_class_via_loader(
        env,
        class_loader,
        "dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskEntry",
    );
    if class.is_null() {
        loge!("Zygisk: failed to load ZygiskEntry class");
        return false;
    }

    let mut methods: [JNINativeMethod; 4] = [
        JNINativeMethod {
            name: c"nativeInitialize".as_ptr() as *mut c_char,
            signature: c"()Z".as_ptr() as *mut c_char,
            fnPtr: jni_native_initialize as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeHasTelegramRootCompanion".as_ptr() as *mut c_char,
            signature: c"()Z".as_ptr() as *mut c_char,
            fnPtr: jni_has_telegram_root_companion as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeListTelegramInstances".as_ptr() as *mut c_char,
            signature: c"()[Ljava/lang/String;".as_ptr() as *mut c_char,
            fnPtr: jni_list_telegram_instances as *mut c_void,
        },
        JNINativeMethod {
            name: c"nativeCopyTelegramDatabaseSnapshot".as_ptr() as *mut c_char,
            signature: c"(Ljava/lang/String;III)I".as_ptr() as *mut c_char,
            fnPtr: jni_copy_telegram_database_snapshot as *mut c_void,
        },
    ];

    let fns = *env;
    let ret = ((*fns).v1_6.RegisterNatives)(env, class, methods.as_mut_ptr(), 4);
    if ret == 0 {
        logi!("Zygisk: ZygiskEntry natives registered");
        true
    } else {
        loge!("Zygisk: RegisterNatives(ZygiskEntry) failed: {ret}");
        false
    }
}
