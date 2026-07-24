#![allow(unused)]

mod logging;
mod zygisk;

use std::ffi::c_void;

use jni::sys::JNIEnv as RawJNIEnv;
use libc::c_int;
use zygisk::{AppSpecializeArgs, ModuleAbi, ServerSpecializeArgs};

use crate::zygisk::ApiTable;

// Placeholder WeKitModule — will be fleshed out in Task 5
struct WeKitModule {
    api: *mut ApiTable,
    env: *mut RawJNIEnv,
}

extern "C" fn pre_app(_m: *mut c_void, _args: *mut AppSpecializeArgs) {}

extern "C" fn post_app(_m: *mut c_void, _args: *const AppSpecializeArgs) {}

extern "C" fn pre_server(m: *mut c_void, _args: *mut ServerSpecializeArgs) {
    // Not injecting into system_server: dlclose
    unsafe {
        let module = &mut *(m as *mut WeKitModule);
        (*module.api).set_option(zygisk::DLCLOSE_MODULE_LIBRARY);
    }
}

extern "C" fn post_server(_m: *mut c_void, _args: *const ServerSpecializeArgs) {}

/// # Safety
///
/// Called exclusively by the Zygisk framework with a valid `api_table` and `JNIEnv`.
/// Both pointers must be non-null and remain valid for the lifetime of the module.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn zygisk_module_entry(table: *mut ApiTable, env: *mut RawJNIEnv) {
    let module = Box::leak(Box::new(WeKitModule { api: table, env }));
    let abi = Box::leak(Box::new(ModuleAbi {
        api_version: 4,
        impl_ptr: module as *mut WeKitModule as *mut c_void,
        pre_app_specialize: pre_app,
        post_app_specialize: post_app,
        pre_server_specialize: pre_server,
        post_server_specialize: post_server,
    }));
    unsafe {
        ((*table).register_module)(table, abi);
    }
}

/// # Safety
///
/// Called exclusively by the Zygisk framework with a valid connected Unix domain socket fd.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn zygisk_companion_entry(_sock: c_int) {
    // placeholder — companion implemented in Task 4
}
