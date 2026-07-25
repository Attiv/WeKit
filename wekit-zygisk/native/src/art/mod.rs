// art/mod.rs — Public API stubs + global state (full impl in Task 10)
pub mod elf;
pub mod layout;
pub mod trampoline;

use jni::sys::{JNIEnv as RawJNIEnv, jclass, jobject};

pub fn init(_env: *mut RawJNIEnv) -> bool {
    false
}
pub fn is_initialized() -> bool {
    false
}
pub fn get_art_method(_env: *mut RawJNIEnv, _executable: jobject) -> usize {
    0
}
pub fn hook_method(_env: *mut RawJNIEnv, _target: usize, _backup: usize, _bridge: usize) -> i32 {
    -1
}
pub fn unhook_method(_env: *mut RawJNIEnv, _target: usize, _backup: usize) -> i32 {
    -1
}
pub fn trust_dex_file(_env: *mut RawJNIEnv, _dex_file: jobject) -> bool {
    false
}
pub fn trust_class_loader(_env: *mut RawJNIEnv, _class_loader: jobject) -> bool {
    false
}
pub fn allocate_instance(_env: *mut RawJNIEnv, _cls: jclass) -> jobject {
    std::ptr::null_mut()
}
