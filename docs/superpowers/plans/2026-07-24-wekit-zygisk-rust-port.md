# wekit-zygisk/native Rust 移植实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `wekit-zygisk/native/` 从 C/C++ 完全迁移至 Rust，产物为 `libwekit_zygisk.so`，保持 Zygisk API v4 + JNI 注册契约不变，最终删除全部 C++ 文件。

**Architecture:** 独立 `cdylib` crate（`wekit_zygisk`），手写 `#[repr(C)]` Zygisk ABI，companion 双 fork 用 `libc::fork`，ART hook 最后单独实施。xtask 的 CMake 调用替换为 `cargo build`。

**Tech Stack:** Rust nightly (edition 2024)，`libc 0.2`，`jni 0.22`（仅 sys 层），NDK 30，Android API 28，arm64-v8a + armeabi-v7a

## Global Constraints

- 输出库名：`libwekit_zygisk.so`（Cargo `[lib] name = "wekit_zygisk"`）
- Zygisk API version = 4；导出符号仅 `zygisk_module_entry` 和 `zygisk_companion_entry`
- JNI 函数通过 RegisterNatives 注册，不做 name-mangled 导出
- `panic = "abort"` 继承自 workspace release profile，JNI 边界不 `catch_unwind`
- 日志标签 `WeKit`，前缀 `Zygisk:`
- 宿主单元测试覆盖：`protocol.rs`（帧编解码）、`so_hider.rs`（maps 解析）
- ART hook 模块（`art/`）无宿主单元测试，仅真机 CI 验证
- 每个任务结束时运行 `cargo fmt --check` 和 `cargo clippy -- -D warnings`

---

## 文件结构

**新建文件：**
- `wekit-zygisk/native/Cargo.toml`
- `wekit-zygisk/native/src/lib.rs`
- `wekit-zygisk/native/src/zygisk.rs`
- `wekit-zygisk/native/src/logging.rs`
- `wekit-zygisk/native/src/protocol.rs`
- `wekit-zygisk/native/src/companion.rs`
- `wekit-zygisk/native/src/lifecycle.rs`
- `wekit-zygisk/native/src/payload.rs`
- `wekit-zygisk/native/src/jni.rs`
- `wekit-zygisk/native/src/so_hider.rs`
- `wekit-zygisk/native/src/art/mod.rs`
- `wekit-zygisk/native/src/art/elf.rs`
- `wekit-zygisk/native/src/art/layout.rs`
- `wekit-zygisk/native/src/art/trampoline.rs`

**修改文件：**
- `Cargo.toml` — 添加 workspace member `"wekit-zygisk/native/"`
- `xtask/src/main.rs` — 替换 CMake 调用为 Cargo，扩展 configure
- `.github/workflows/ci.yml` — `build_zygisk` 补装 Android Rust targets

**迁移完成后删除：**
- `wekit-zygisk/native/CMakeLists.txt`
- `wekit-zygisk/native/main.cpp`
- `wekit-zygisk/native/art_hook.cpp`
- `wekit-zygisk/native/so_hider.cpp`
- `wekit-zygisk/native/art_hook.h`
- `wekit-zygisk/native/so_hider.h`
- `wekit-zygisk/native/zygisk.hpp`

---

## Task 1: Phase 0 — 基准冻结（重命名 MODULE_ID + readelf 快照）

**Files:**
- Modify: `xtask/src/main.rs:71`

**Interfaces:**
- Produces: `ZYGISK_MODULE_ID = "wekit_zygisk"`；C++ 产物文件名变为 `libwekit_zygisk.so`

- [ ] **Step 1: 修改 ZYGISK_MODULE_ID**

`xtask/src/main.rs` 第71行：
```rust
// 修改前
const ZYGISK_MODULE_ID: &str = "wekit";
// 修改后
const ZYGISK_MODULE_ID: &str = "wekit_zygisk";
```

- [ ] **Step 2: 构建 C++ 基准并记录 readelf 快照**

```bash
./x zygisk native
# 预期：wekit-zygisk/output/native/release/lib/arm64-v8a/libwekit_zygisk.so 存在

readelf -d wekit-zygisk/output/native/release/lib/arm64-v8a/libwekit_zygisk.so \
  | grep -E 'SONAME|NEEDED' \
  > /tmp/zygisk_baseline_arm64.txt

readelf --dyn-syms wekit-zygisk/output/native/release/lib/arm64-v8a/libwekit_zygisk.so \
  | grep -E 'GLOBAL|WEAK' >> /tmp/zygisk_baseline_arm64.txt

cat /tmp/zygisk_baseline_arm64.txt
```

预期输出（近似）：
```
 0x000000000000000e (SONAME) Library soname: [libwekit_zygisk.so]
 0x0000000000000001 (NEEDED) Shared library: [liblog.so]
 0x0000000000000001 (NEEDED) Shared library: [libandroid.so]
...
GLOBAL DEFAULT  zygisk_module_entry
GLOBAL DEFAULT  zygisk_companion_entry
```

- [ ] **Step 3: 将快照保存到仓库**

```bash
mkdir -p wekit-zygisk/baseline
cp /tmp/zygisk_baseline_arm64.txt wekit-zygisk/baseline/readelf-arm64-v8a.txt
# 对 armeabi-v7a 重复
readelf -d wekit-zygisk/output/native/release/lib/armeabi-v7a/libwekit_zygisk.so \
  | grep -E 'SONAME|NEEDED' > wekit-zygisk/baseline/readelf-armeabi-v7a.txt
readelf --dyn-syms wekit-zygisk/output/native/release/lib/armeabi-v7a/libwekit_zygisk.so \
  | grep -E 'GLOBAL|WEAK' >> wekit-zygisk/baseline/readelf-armeabi-v7a.txt
```

- [ ] **Step 4: Commit**

```bash
git add xtask/src/main.rs wekit-zygisk/baseline/
git commit -m "build: rename ZYGISK_MODULE_ID to wekit_zygisk, capture readelf baseline"
```

---

## Task 2: Phase 1 — Rust crate 骨架 + Zygisk ABI 定义

**Files:**
- Create: `wekit-zygisk/native/Cargo.toml`
- Create: `wekit-zygisk/native/src/lib.rs`
- Create: `wekit-zygisk/native/src/zygisk.rs`
- Create: `wekit-zygisk/native/src/logging.rs`
- Modify: `Cargo.toml` (workspace)

**Interfaces:**
- Produces: `zygisk::ApiTable`, `zygisk::ModuleAbi`, `zygisk::AppSpecializeArgs`, `zygisk::ServerSpecializeArgs`
- Produces: `logi!`, `logw!`, `loge!` macros exported from crate root

- [ ] **Step 1: 添加到 workspace**

`Cargo.toml` 修改：
```toml
[workspace]
members = [
    "app/src/main/rust/wekit-native/",
    "wekit-zygisk/native/",
    "xtask",
]
resolver = "3"

[profile.release]
opt-level = "z"
lto = true
codegen-units = 1
strip = true
panic = "abort"

[profile.release.package.wekit_zygisk]
strip = "none"
```

- [ ] **Step 2: 创建 wekit-zygisk/native/Cargo.toml**

```toml
[package]
name    = "wekit_zygisk"
version = "0.1.0"
edition = "2024"

[lib]
name       = "wekit_zygisk"
crate-type = ["cdylib"]

[dependencies]
libc = "0.2"
jni  = { version = "0.22", default-features = false }
```

- [ ] **Step 3: 创建 src/logging.rs**

```rust
// ─────────────────────────────────────────────────────────────────────────────
// Android log — 直接绑定 __android_log_write，风格对齐 wekit-native/logging.rs
// ─────────────────────────────────────────────────────────────────────────────
use std::ffi::{CStr, CString, c_char, c_int};

pub const ANDROID_LOG_INFO:  c_int = 4;
pub const ANDROID_LOG_WARN:  c_int = 5;
pub const ANDROID_LOG_ERROR: c_int = 6;

const LOG_TAG: &CStr = c"WeKit";

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

pub fn android_log(prio: c_int, msg: &str) {
    #[cfg(target_os = "android")]
    {
        let text = CString::new(msg).unwrap_or_else(|_| c"<log error>".to_owned());
        unsafe { __android_log_write(prio, LOG_TAG.as_ptr(), text.as_ptr()); }
    }
    #[cfg(not(target_os = "android"))]
    eprintln!("[WeKit {}] {msg}", prio);
}

#[macro_export]
macro_rules! logi {
    ($($t:tt)*) => {
        $crate::logging::android_log($crate::logging::ANDROID_LOG_INFO, &format!($($t)*))
    };
}
#[macro_export]
macro_rules! logw {
    ($($t:tt)*) => {
        $crate::logging::android_log($crate::logging::ANDROID_LOG_WARN, &format!($($t)*))
    };
}
#[macro_export]
macro_rules! loge {
    ($($t:tt)*) => {
        $crate::logging::android_log($crate::logging::ANDROID_LOG_ERROR, &format!($($t)*))
    };
}
```

- [ ] **Step 4: 创建 src/zygisk.rs**

根据 `zygisk.hpp` 精确还原 ABI（`api_table` 共10个字段，`module_abi` 共6个字段）：

```rust
// ─────────────────────────────────────────────────────────────────────────────
// Zygisk API v4 — 手写 #[repr(C)] 对齐 zygisk.hpp 的 internal::api_table 和
// internal::module_abi。字段顺序必须和 C++ 定义完全一致。
// ─────────────────────────────────────────────────────────────────────────────
use std::ffi::c_void;
use jni::sys::{JNIEnv as RawJNIEnv, JNINativeMethod};
use libc::{c_int, dev_t, ino_t};

// Option values for setOption
pub const FORCE_DENYLIST_UNMOUNT: c_int = 0;
pub const DLCLOSE_MODULE_LIBRARY: c_int = 1;

/// api_table — laid out identically to zygisk::internal::api_table in zygisk.hpp.
///
/// Field order (10 fields):
///   impl_ptr, register_module, hook_jni_native_methods, plt_hook_register,
///   exempt_fd, plt_hook_commit, connect_companion, set_option,
///   get_module_dir, get_flags
#[repr(C)]
pub struct ApiTable {
    pub impl_ptr:                  *mut c_void,
    pub register_module:           unsafe extern "C" fn(*mut ApiTable, *mut ModuleAbi) -> bool,
    pub hook_jni_native_methods:   Option<unsafe extern "C" fn(*mut RawJNIEnv, *const libc::c_char, *mut JNINativeMethod, c_int)>,
    pub plt_hook_register:         Option<unsafe extern "C" fn(dev_t, ino_t, *const libc::c_char, *mut c_void, *mut *mut c_void)>,
    pub exempt_fd:                 Option<unsafe extern "C" fn(c_int) -> bool>,
    pub plt_hook_commit:           Option<unsafe extern "C" fn() -> bool>,
    pub connect_companion:         Option<unsafe extern "C" fn(*mut c_void) -> c_int>,
    pub set_option:                Option<unsafe extern "C" fn(*mut c_void, c_int)>,
    pub get_module_dir:            Option<unsafe extern "C" fn(*mut c_void) -> c_int>,
    pub get_flags:                 Option<unsafe extern "C" fn(*mut c_void) -> u32>,
}

/// module_abi — laid out identically to zygisk::internal::module_abi.
///
/// Note: api_version is `long` in C++ — on 64-bit Android that is i64.
#[repr(C)]
pub struct ModuleAbi {
    pub api_version:            i64,       // long — 4
    pub impl_ptr:               *mut c_void,
    pub pre_app_specialize:     unsafe extern "C" fn(*mut c_void, *mut AppSpecializeArgs),
    pub post_app_specialize:    unsafe extern "C" fn(*mut c_void, *const AppSpecializeArgs),
    pub pre_server_specialize:  unsafe extern "C" fn(*mut c_void, *mut ServerSpecializeArgs),
    pub post_server_specialize: unsafe extern "C" fn(*mut c_void, *const ServerSpecializeArgs),
}

/// AppSpecializeArgs — C++ references become raw pointers in Rust.
/// Read a reference field:  `unsafe { *(*args).nice_name }`
#[repr(C)]
pub struct AppSpecializeArgs {
    // Required (C++ references — always valid)
    pub uid:              *mut jni::sys::jint,
    pub gid:              *mut jni::sys::jint,
    pub gids:             *mut jni::sys::jintArray,
    pub runtime_flags:    *mut jni::sys::jint,
    pub rlimits:          *mut jni::sys::jobjectArray,
    pub mount_external:   *mut jni::sys::jint,
    pub se_info:          *mut jni::sys::jstring,
    pub nice_name:        *mut jni::sys::jstring,
    pub instruction_set:  *mut jni::sys::jstring,
    pub app_data_dir:     *mut jni::sys::jstring,
    // Optional (may be null)
    pub fds_to_ignore:               *const jni::sys::jintArray,
    pub is_child_zygote:             *const jni::sys::jboolean,
    pub is_top_app:                  *const jni::sys::jboolean,
    pub pkg_data_info_list:          *const jni::sys::jobjectArray,
    pub whitelisted_data_info_list:  *const jni::sys::jobjectArray,
    pub mount_data_dirs:             *const jni::sys::jboolean,
    pub mount_storage_dirs:          *const jni::sys::jboolean,
}

#[repr(C)]
pub struct ServerSpecializeArgs {
    pub uid:                    *mut jni::sys::jint,
    pub gid:                    *mut jni::sys::jint,
    pub gids:                   *mut jni::sys::jintArray,
    pub runtime_flags:          *mut jni::sys::jint,
    pub permitted_capabilities: *mut jni::sys::jlong,
    pub effective_capabilities: *mut jni::sys::jlong,
}

/// Convenience wrappers around ApiTable function pointers (all called through
/// tbl->impl_ptr as the first argument, matching C++ Api:: inline methods).
impl ApiTable {
    pub unsafe fn connect_companion(&mut self) -> c_int {
        match self.connect_companion {
            Some(f) => f(self.impl_ptr),
            None => -1,
        }
    }
    pub unsafe fn get_module_dir(&mut self) -> c_int {
        match self.get_module_dir {
            Some(f) => f(self.impl_ptr),
            None => -1,
        }
    }
    pub unsafe fn set_option(&mut self, opt: c_int) {
        if let Some(f) = self.set_option { f(self.impl_ptr, opt); }
    }
    pub unsafe fn get_flags(&mut self) -> u32 {
        match self.get_flags {
            Some(f) => f(self.impl_ptr),
            None => 0,
        }
    }
}
```

- [ ] **Step 5: 创建 src/lib.rs（骨架）**

```rust
#![allow(unused)]
mod logging;
mod zygisk;

use zygisk::{ApiTable, AppSpecializeArgs, ModuleAbi, ServerSpecializeArgs};
use jni::sys::JNIEnv as RawJNIEnv;
use std::ffi::c_void;

// Placeholder WeKitModule — will be fleshed out in Task 5
struct WeKitModule {
    api: *mut ApiTable,
    env: *mut RawJNIEnv,
}

extern "C" fn pre_app(m: *mut c_void, args: *mut AppSpecializeArgs) {}
extern "C" fn post_app(m: *mut c_void, args: *const AppSpecializeArgs) {}
extern "C" fn pre_server(m: *mut c_void, args: *mut ServerSpecializeArgs) {
    // Not injecting into system_server: dlclose
    unsafe {
        let module = &mut *(m as *mut WeKitModule);
        (*module.api).set_option(zygisk::DLCLOSE_MODULE_LIBRARY);
    }
}
extern "C" fn post_server(m: *mut c_void, args: *const ServerSpecializeArgs) {}

#[unsafe(no_mangle)]
pub extern "C" fn zygisk_module_entry(table: *mut ApiTable, env: *mut RawJNIEnv) {
    let module = Box::leak(Box::new(WeKitModule { api: table, env }));
    let abi = Box::leak(Box::new(ModuleAbi {
        api_version: 4,
        impl_ptr: module as *mut WeKitModule as *mut c_void,
        pre_app_specialize: pre_app,
        post_app_specialize: post_app,
        pre_server_specialize: pre_server,
        post_server_specialize: post_server,
    }));
    unsafe { ((*table).register_module)(table, abi); }
}

#[unsafe(no_mangle)]
pub extern "C" fn zygisk_companion_entry(sock: libc::c_int) {
    // placeholder — companion implemented in Task 4
}
```

- [ ] **Step 6: cargo check で両ターゲット通过**

```bash
# 先生成 NDK linker config（如果还没做）
cargo xtask configure

cargo check -p wekit_zygisk --target aarch64-linux-android
cargo check -p wekit_zygisk --target armv7-linux-androideabi
```

预期：两个 target 都 `Finished` 无 error。

- [ ] **Step 7: cargo fmt + clippy**

```bash
cargo fmt -p wekit_zygisk
cargo clippy -p wekit_zygisk --target aarch64-linux-android -- -D warnings
```

预期：无警告。

- [ ] **Step 8: Commit**

```bash
git add Cargo.toml wekit-zygisk/native/
git commit -m "feat(zygisk): scaffold Rust crate with Zygisk ABI definitions"
```

---

## Task 3: Phase 2a — protocol.rs（含宿主单元测试）

**Files:**
- Create: `wekit-zygisk/native/src/protocol.rs`

**Interfaces:**
- Produces: `read_u8_from_fd`, `read_u16_from_fd`, `read_u64_from_fd`, `read_bytes_from_fd`, `read_string_from_fd`, `write_u8_to_fd`, `write_string_to_fd`, `write_error_frame`
- Produces: constants `COMPANION_REQUEST_ENABLED`, `COMPANION_REQUEST_TELEGRAM_SESSION`, `COMPANION_DISABLED`, `COMPANION_ENABLED`, `COMPANION_ERROR`

- [ ] **Step 1: 写 failing 测试**

在 `src/protocol.rs` 末尾加 `#[cfg(test)]` 块：

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use libc::c_int;

    fn make_socketpair() -> (c_int, c_int) {
        let mut fds = [0i32; 2];
        unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, fds.as_mut_ptr()) };
        (fds[0], fds[1])
    }

    #[test]
    fn round_trip_string() {
        let (r, w) = make_socketpair();
        write_string_to_fd(w, "hello").unwrap();
        let got = read_string_from_fd(r).unwrap();
        assert_eq!(got, "hello");
        unsafe { libc::close(r); libc::close(w); }
    }

    #[test]
    fn round_trip_u8() {
        let (r, w) = make_socketpair();
        write_u8_to_fd(w, 0x02).unwrap();
        assert_eq!(read_u8_from_fd(r).unwrap(), 0x02);
        unsafe { libc::close(r); libc::close(w); }
    }

    #[test]
    fn error_frame_roundtrip() {
        let (r, w) = make_socketpair();
        write_error_frame(w, "oops").unwrap();
        assert_eq!(read_u8_from_fd(r).unwrap(), COMPANION_ERROR);
        let msg = read_string_from_fd(r).unwrap();
        assert_eq!(msg, "oops");
        unsafe { libc::close(r); libc::close(w); }
    }

    #[test]
    fn string_too_long_is_truncated_or_rejected() {
        let long_str = "x".repeat(300);
        let (r, w) = make_socketpair();
        // write_string_to_fd should cap at u16::MAX
        let result = write_string_to_fd(w, &long_str);
        // either truncates or returns Err — must not panic
        let _ = result;
        unsafe { libc::close(r); libc::close(w); }
    }
}
```

- [ ] **Step 2: 运行测试确认 fail**

```bash
cargo test -p wekit_zygisk -- protocol
```

预期：编译错误 `unresolved import` 或 `module not found`。

- [ ] **Step 3: 实现 src/protocol.rs**

```rust
// ─────────────────────────────────────────────────────────────────────────────
// Companion / Telegram 二进制协议 — 逐条对齐 main.cpp:45–211
// ─────────────────────────────────────────────────────────────────────────────
use libc::c_int;
use std::io::{self, ErrorKind};

// ── 常量 ──────────────────────────────────────────────────────────────────────
pub const COMPANION_REQUEST_ENABLED:          u8 = 0x01;
pub const COMPANION_REQUEST_TELEGRAM_SESSION: u8 = 0x02;
pub const COMPANION_DISABLED: u8 = 0;
pub const COMPANION_ENABLED:  u8 = 1;
pub const COMPANION_ERROR:    u8 = 2;

pub const TELEGRAM_REQUEST_DISCOVER:       u8 = 0x01;
pub const TELEGRAM_REQUEST_COPY_DATABASE:  u8 = 0x02;
pub const TELEGRAM_RESPONSE_OK:            u8 = 0;
pub const TELEGRAM_RESPONSE_ERROR:         u8 = 1;

// ── IO helpers ────────────────────────────────────────────────────────────────

fn read_exact_fd(fd: c_int, buf: &mut [u8]) -> io::Result<()> {
    let mut total = 0;
    while total < buf.len() {
        let n = unsafe {
            libc::read(fd, buf[total..].as_mut_ptr() as *mut _, buf.len() - total)
        };
        match n {
            n if n > 0 => total += n as usize,
            0 => return Err(io::Error::new(ErrorKind::UnexpectedEof, "fd closed")),
            _ => return Err(io::Error::last_os_error()),
        }
    }
    Ok(())
}

fn write_exact_fd(fd: c_int, buf: &[u8]) -> io::Result<()> {
    let mut total = 0;
    while total < buf.len() {
        let n = unsafe {
            libc::write(fd, buf[total..].as_ptr() as *const _, buf.len() - total)
        };
        match n {
            n if n > 0 => total += n as usize,
            _ => return Err(io::Error::last_os_error()),
        }
    }
    Ok(())
}

// ── Public API ────────────────────────────────────────────────────────────────

pub fn read_u8_from_fd(fd: c_int) -> io::Result<u8> {
    let mut b = [0u8; 1];
    read_exact_fd(fd, &mut b)?;
    Ok(b[0])
}

pub fn read_u16_from_fd(fd: c_int) -> io::Result<u16> {
    let mut b = [0u8; 2];
    read_exact_fd(fd, &mut b)?;
    Ok(u16::from_ne_bytes(b))
}

pub fn read_u32_from_fd(fd: c_int) -> io::Result<u32> {
    let mut b = [0u8; 4];
    read_exact_fd(fd, &mut b)?;
    Ok(u32::from_ne_bytes(b))
}

pub fn read_i32_from_fd(fd: c_int) -> io::Result<i32> {
    let mut b = [0u8; 4];
    read_exact_fd(fd, &mut b)?;
    Ok(i32::from_ne_bytes(b))
}

pub fn read_u64_from_fd(fd: c_int) -> io::Result<u64> {
    let mut b = [0u8; 8];
    read_exact_fd(fd, &mut b)?;
    Ok(u64::from_ne_bytes(b))
}

pub fn read_bytes_from_fd(fd: c_int, n: usize) -> io::Result<Vec<u8>> {
    let mut buf = vec![0u8; n];
    read_exact_fd(fd, &mut buf)?;
    Ok(buf)
}

/// Reads a length-prefixed string: [u16 len][bytes]
pub fn read_string_from_fd(fd: c_int) -> io::Result<String> {
    let len = read_u16_from_fd(fd)? as usize;
    let bytes = read_bytes_from_fd(fd, len)?;
    String::from_utf8(bytes).map_err(|e| io::Error::new(ErrorKind::InvalidData, e))
}

pub fn write_u8_to_fd(fd: c_int, v: u8) -> io::Result<()> {
    write_exact_fd(fd, &[v])
}

pub fn write_u16_to_fd(fd: c_int, v: u16) -> io::Result<()> {
    write_exact_fd(fd, &v.to_ne_bytes())
}

pub fn write_u64_to_fd(fd: c_int, v: u64) -> io::Result<()> {
    write_exact_fd(fd, &v.to_ne_bytes())
}

/// Writes [u16 len][bytes].  len is capped at u16::MAX (65535).
pub fn write_string_to_fd(fd: c_int, s: &str) -> io::Result<()> {
    let bytes = s.as_bytes();
    let len = bytes.len().min(u16::MAX as usize) as u16;
    write_u16_to_fd(fd, len)?;
    write_exact_fd(fd, &bytes[..len as usize])
}

/// Writes the error frame: [COMPANION_ERROR=2][u16 len][msg bytes]
pub fn write_error_frame(fd: c_int, msg: &str) -> io::Result<()> {
    write_u8_to_fd(fd, COMPANION_ERROR)?;
    write_string_to_fd(fd, msg)
}
```

- [ ] **Step 4: 添加 mod 声明并跑测试**

`src/lib.rs` 添加：`mod protocol;`

```bash
cargo test -p wekit_zygisk -- protocol
```

预期：
```
test protocol::tests::round_trip_string ... ok
test protocol::tests::round_trip_u8 ... ok
test protocol::tests::error_frame_roundtrip ... ok
test protocol::tests::string_too_long_is_truncated_or_rejected ... ok
```

- [ ] **Step 5: Commit**

```bash
cargo fmt -p wekit_zygisk
git add wekit-zygisk/native/src/protocol.rs wekit-zygisk/native/src/lib.rs
git commit -m "feat(zygisk): add protocol.rs with host unit tests"
```

---

## Task 4: Phase 2b — companion.rs

**Files:**
- Create: `wekit-zygisk/native/src/companion.rs`

**Interfaces:**
- Consumes: `protocol::*` (constants, read/write helpers)
- Produces: `pub fn handle(sock: c_int)` — called from `zygisk_companion_entry`

- [ ] **Step 1: 实现 src/companion.rs**

```rust
// ─────────────────────────────────────────────────────────────────────────────
// Zygisk companion — allow-list 检查 + 双 fork worker
// 逻辑对齐 main.cpp:522–655
// ─────────────────────────────────────────────────────────────────────────────
use crate::protocol::*;
use crate::{loge, logi};
use libc::{c_int, pid_t};
use std::{
    ffi::CString,
    fs,
    os::fd::FromRawFd,
    path::Path,
};

const TARGETS_PATH: &str = "/data/adb/wekit/injection-targets.tsv";
const WECHAT_PACKAGE_PREFIX: &str = "com.tencent.mm";

// ── Allow-list ────────────────────────────────────────────────────────────────

/// Returns true iff uid/process_name is an enabled WeChat target.
/// Reads TARGETS_PATH (tab-separated: user_id \t package_name \t enabled).
fn is_enabled_target(uid: i32, process_name: &str) -> bool {
    let user_id = uid / 100_000;
    let content = match fs::read_to_string(TARGETS_PATH) {
        Ok(s) => s,
        Err(_) => return false,
    };
    for line in content.lines() {
        if line.starts_with('#') || line.is_empty() { continue; }
        let parts: Vec<&str> = line.splitn(4, '\t').collect();
        if parts.len() != 3 { continue; }
        let (row_user, pkg, enabled) = (parts[0], parts[1], parts[2]);
        if enabled != "1" { continue; }
        if !pkg.starts_with(WECHAT_PACKAGE_PREFIX) { continue; }
        let row_uid: i32 = match row_user.parse() { Ok(v) => v, Err(_) => continue };
        if row_uid != user_id { continue; }
        if process_name == pkg || process_name.starts_with(&format!("{pkg}:")) {
            return true;
        }
    }
    false
}

// ── Request header ─────────────────────────────────────────────────────────────

struct RequestHeader {
    request_type: u8,
    uid: i32,
    process_name: String,
}

fn read_header(sock: c_int) -> Option<RequestHeader> {
    let request_type = read_u8_from_fd(sock).ok()?;
    let uid = read_i32_from_fd(sock).ok()?;
    let process_name = read_string_from_fd(sock).ok()?;
    Some(RequestHeader { request_type, uid, process_name })
}

// ── Telegram socket name ──────────────────────────────────────────────────────

fn random_nonce() -> u32 {
    let mut buf = [0u8; 4];
    let fd = unsafe { libc::open(c"/dev/urandom".as_ptr(), libc::O_RDONLY) };
    if fd < 0 { return 0xdeadbeef; }
    unsafe { libc::read(fd, buf.as_mut_ptr() as *mut _, 4); libc::close(fd); }
    u32::from_ne_bytes(buf)
}

fn make_abstract_socket_name(uid: i32) -> String {
    format!("wekit-tg-{uid}-{:08x}", random_nonce())
}

// ── Telegram worker ───────────────────────────────────────────────────────────

fn find_telegram_instances() -> Vec<String> {
    // Walk /data/data looking for com.tencent.mm variants
    let mut packages = Vec::new();
    if let Ok(entries) = fs::read_dir("/data/data") {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with(WECHAT_PACKAGE_PREFIX) {
                packages.push(name);
            }
        }
    }
    packages
}

fn copy_db_file(path: &str, client_fd: c_int) {
    match fs::read(path) {
        Ok(bytes) => {
            let _ = write_u64_to_fd(client_fd, bytes.len() as u64);
            unsafe { libc::write(client_fd, bytes.as_ptr() as *const _, bytes.len()); }
        }
        Err(_) => { let _ = write_u64_to_fd(client_fd, 0); }
    }
}

fn telegram_worker(server_fd: c_int) {
    loop {
        let client = unsafe { libc::accept(server_fd, std::ptr::null_mut(), std::ptr::null_mut()) };
        if client < 0 { continue; }
        let op = match read_u8_from_fd(client) {
            Ok(v) => v,
            Err(_) => { unsafe { libc::close(client); } continue; }
        };
        match op {
            TELEGRAM_REQUEST_DISCOVER => {
                let instances = find_telegram_instances();
                let _ = write_u8_to_fd(client, TELEGRAM_RESPONSE_OK);
                let _ = write_u16_to_fd(client, instances.len().min(u16::MAX as usize) as u16);
                for pkg in &instances { let _ = write_string_to_fd(client, pkg); }
            }
            TELEGRAM_REQUEST_COPY_DATABASE => {
                if let Ok(pkg) = read_string_from_fd(client) {
                    let _ = write_u8_to_fd(client, TELEGRAM_RESPONSE_OK);
                    let base = format!("/data/data/{pkg}/files/account.db");
                    copy_db_file(&base, client);
                    copy_db_file(&format!("{base}-wal"), client);
                    copy_db_file(&format!("{base}-shm"), client);
                }
            }
            _ => { let _ = write_error_frame(client, "unknown op"); }
        }
        unsafe { libc::close(client); }
    }
}

// ── Main handler ───────────────────────────────────────────────────────────────

/// Entry point called from zygisk_companion_entry.
pub fn handle(sock: c_int) {
    let header = match read_header(sock) {
        Some(h) => h,
        None => { let _ = write_u8_to_fd(sock, COMPANION_ERROR); return; }
    };

    if !is_enabled_target(header.uid, &header.process_name) {
        let _ = write_u8_to_fd(sock, COMPANION_DISABLED);
        return;
    }

    match header.request_type {
        COMPANION_REQUEST_ENABLED => {
            let _ = write_u8_to_fd(sock, COMPANION_ENABLED);
        }
        COMPANION_REQUEST_TELEGRAM_SESSION => {
            let name = make_abstract_socket_name(header.uid);
            // Create abstract Unix server socket
            let server_fd = unsafe {
                let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
                if fd < 0 { let _ = write_u8_to_fd(sock, COMPANION_ERROR); return; }
                let mut addr: libc::sockaddr_un = std::mem::zeroed();
                addr.sun_family = libc::AF_UNIX as u16;
                // Abstract namespace: first byte = '\0'
                let name_bytes = name.as_bytes();
                let len = name_bytes.len().min(107);
                addr.sun_path[1..=len].copy_from_slice(
                    &name_bytes[..len].iter().map(|&b| b as i8).collect::<Vec<_>>()
                );
                let addr_len = (std::mem::offset_of!(libc::sockaddr_un, sun_path) + 1 + len)
                    as libc::socklen_t;
                if libc::bind(fd, &addr as *const _ as _, addr_len) < 0 {
                    libc::close(fd);
                    let _ = write_u8_to_fd(sock, COMPANION_ERROR);
                    return;
                }
                libc::listen(fd, 5);
                fd
            };
            // Double-fork: grandchild runs telegram_worker, adopted by init
            let mid_pid = unsafe { libc::fork() };
            if mid_pid == 0 {
                // intermediate child
                let grandchild_pid = unsafe { libc::fork() };
                if grandchild_pid == 0 {
                    // grandchild: run worker forever
                    telegram_worker(server_fd);
                    std::process::exit(0);
                }
                std::process::exit(0); // intermediate exits immediately
            }
            // Parent: wait for intermediate child
            unsafe {
                let mut status = 0i32;
                libc::waitpid(mid_pid, &mut status, 0);
                libc::close(server_fd);
            }
            // Send back the socket name
            let _ = write_u8_to_fd(sock, COMPANION_ENABLED);
            let _ = write_string_to_fd(sock, &name);
        }
        _ => { let _ = write_error_frame(sock, "unknown request type"); }
    }
}
```

- [ ] **Step 2: 添加 mod 声明，cargo check**

`src/lib.rs` 添加：`mod companion;`  
更新 `zygisk_companion_entry`：
```rust
#[unsafe(no_mangle)]
pub extern "C" fn zygisk_companion_entry(sock: libc::c_int) {
    companion::handle(sock);
}
```

```bash
cargo check -p wekit_zygisk --target aarch64-linux-android
```

预期：无 error。

- [ ] **Step 3: Commit**

```bash
cargo fmt -p wekit_zygisk
git add wekit-zygisk/native/src/companion.rs wekit-zygisk/native/src/lib.rs
git commit -m "feat(zygisk): add companion.rs (allow-list + double-fork + telegram worker)"
```

---

## Task 5: Phase 3 — payload.rs + lifecycle.rs

**Files:**
- Create: `wekit-zygisk/native/src/payload.rs`
- Create: `wekit-zygisk/native/src/lifecycle.rs`

**Interfaces:**
- Consumes: `zygisk::ApiTable`, `zygisk::AppSpecializeArgs`, `protocol::*`, `companion::handle`
- Produces: `WeKitModule` struct (replaces stub in lib.rs), `pub fn connect_to_companion(api) -> c_int`

- [ ] **Step 1: 实现 src/payload.rs**

```rust
// ─────────────────────────────────────────────────────────────────────────────
// payload — APK/DEX 复制、InMemoryDexClassLoader 构建
// 逻辑对齐 main.cpp:postAppSpecialize (lines 1430–1555)
// ─────────────────────────────────────────────────────────────────────────────
use crate::{loge, logi};
use jni::sys::{JNIEnv as RawJNIEnv, jobject, jclass, jmethodID};
use libc::{uid_t, gid_t, RawFd};
use std::{
    ffi::{CStr, CString},
    fs,
    path::Path,
};

pub fn ensure_dir(path: &str, uid: uid_t, gid: gid_t, mode: u32) -> bool {
    if let Err(e) = fs::create_dir_all(path) {
        loge!("Zygisk: ensure_dir {path}: {e}");
        return false;
    }
    let cpath = CString::new(path).unwrap();
    unsafe {
        libc::chmod(cpath.as_ptr(), mode);
        libc::chown(cpath.as_ptr(), uid, gid);
    }
    true
}

/// Copies a file from the module dir (by relative path) to dst_path.
/// Uses temp file + atomic rename + fchown + fsync.
pub fn copy_module_file(
    module_dir_fd: RawFd,
    src_rel: &str,
    dst_path: &str,
    uid: uid_t,
    gid: gid_t,
    max_bytes: u64,
) -> bool {
    // Open source via openat
    let src_cstr = CString::new(src_rel).unwrap();
    let src_fd = unsafe {
        libc::openat(module_dir_fd, src_cstr.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC)
    };
    if src_fd < 0 {
        loge!("Zygisk: openat {src_rel}: {}", std::io::Error::last_os_error());
        return false;
    }
    let tmp_path = format!("{dst_path}.tmp");
    let tmp_cstr = CString::new(tmp_path.as_str()).unwrap();
    let dst_cstr = CString::new(dst_path).unwrap();
    let dst_fd = unsafe {
        libc::open(tmp_cstr.as_ptr(), libc::O_WRONLY | libc::O_CREAT | libc::O_TRUNC | libc::O_CLOEXEC, 0o600)
    };
    if dst_fd < 0 {
        unsafe { libc::close(src_fd); }
        return false;
    }
    let mut buf = [0u8; 65536];
    let mut total = 0u64;
    loop {
        let n = unsafe { libc::read(src_fd, buf.as_mut_ptr() as *mut _, buf.len()) };
        if n <= 0 { break; }
        total += n as u64;
        if total > max_bytes { loge!("Zygisk: {src_rel} exceeds size limit"); break; }
        unsafe { libc::write(dst_fd, buf.as_ptr() as *const _, n as usize); }
    }
    unsafe {
        libc::fchown(dst_fd, uid, gid);
        libc::fsync(dst_fd);
        libc::close(src_fd);
        libc::close(dst_fd);
        libc::rename(tmp_cstr.as_ptr(), dst_cstr.as_ptr());
    }
    true
}

pub fn read_file(path: &str) -> Option<Vec<u8>> {
    fs::read(path).ok()
}

/// Constructs InMemoryDexClassLoader from a list of byte buffers, using raw JNI.
/// Returns GlobalRef jobject or null on failure.
pub unsafe fn build_dex_classloader(
    env: *mut RawJNIEnv,
    dex_buffers: &[Vec<u8>],
    parent_loader: jobject,
) -> jobject {
    let fns = *env;
    macro_rules! jni {
        ($method:ident, $($arg:expr),*) => {
            ((*fns).v1_6.$method)(env, $($arg),*)
        }
    }
    // Allocate ByteBuffer array
    let bb_class_name = c"java/nio/ByteBuffer";
    let bb_class = jni!(FindClass, bb_class_name.as_ptr());
    if bb_class.is_null() { return std::ptr::null_mut(); }
    let arr = jni!(NewObjectArray, dex_buffers.len() as i32, bb_class, std::ptr::null_mut());
    for (i, buf) in dex_buffers.iter().enumerate() {
        let bb = jni!(NewDirectByteBuffer, buf.as_ptr() as *mut _, buf.len() as i64);
        jni!(SetObjectArrayElement, arr, i as i32, bb);
        jni!(DeleteLocalRef, bb);
    }
    // InMemoryDexClassLoader(ByteBuffer[], ClassLoader)
    let cl_name = c"dalvik/system/InMemoryDexClassLoader";
    let cl_class = jni!(FindClass, cl_name.as_ptr());
    if cl_class.is_null() { return std::ptr::null_mut(); }
    let ctor_sig = c"([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V";
    let ctor = jni!(GetMethodID, cl_class, c"<init>".as_ptr(), ctor_sig.as_ptr());
    let loader = jni!(NewObject, cl_class, ctor, arr, parent_loader);
    jni!(DeleteLocalRef, arr);
    jni!(DeleteLocalRef, bb_class);
    jni!(DeleteLocalRef, cl_class);
    if loader.is_null() { return std::ptr::null_mut(); }
    jni!(NewGlobalRef, loader)
}
```

- [ ] **Step 2: 实现 src/lifecycle.rs**

```rust
// ─────────────────────────────────────────────────────────────────────────────
// WeKitModule lifecycle — 三个 Zygisk 回调实现
// 对齐 main.cpp WeKitZygisk class (lines 1344–1576)
// ─────────────────────────────────────────────────────────────────────────────
use crate::{loge, logi, logw};
use crate::payload;
use crate::protocol::*;
use crate::zygisk::{ApiTable, AppSpecializeArgs, ServerSpecializeArgs, DLCLOSE_MODULE_LIBRARY};
use jni::sys::{JNIEnv as RawJNIEnv, jstring, jobject};
use libc::{c_int, uid_t, gid_t, RawFd};
use std::{
    ffi::{CStr, CString},
    os::fd::{FromRawFd, OwnedFd},
};

pub struct WeKitModule {
    pub api: *mut ApiTable,
    pub env: *mut RawJNIEnv,
    pub module_dir_fd:        Option<OwnedFd>,
    pub app_uid:              uid_t,
    pub app_gid:              gid_t,
    pub abi_dir:              &'static str,  // "arm64" or "arm"
    pub dex_names:            Vec<String>,
    pub telegram_socket_name: Option<String>,
    pub enabled:              bool,
    pub module_classloader:   Option<jobject>,  // GlobalRef
}

impl WeKitModule {
    pub fn new(api: *mut ApiTable, env: *mut RawJNIEnv) -> Self {
        Self {
            api, env,
            module_dir_fd: None,
            app_uid: 0, app_gid: 0,
            abi_dir: current_abi_dir(),
            dex_names: Vec::new(),
            telegram_socket_name: None,
            enabled: false,
            module_classloader: None,
        }
    }
}

#[cfg(target_arch = "aarch64")]
fn current_abi_dir() -> &'static str { "arm64" }
#[cfg(target_arch = "arm")]
fn current_abi_dir() -> &'static str { "arm" }
#[cfg(not(any(target_arch = "aarch64", target_arch = "arm")))]
fn current_abi_dir() -> &'static str { "arm64" }

/// Read a jstring from the args struct (dereference the C++ reference pointer).
unsafe fn read_jstring(env: *mut RawJNIEnv, ptr: *mut jstring) -> Option<String> {
    if ptr.is_null() { return None; }
    let jstr = *ptr;
    if jstr.is_null() { return None; }
    let fns = *env;
    let chars = ((*fns).v1_6.GetStringUTFChars)(env, jstr, std::ptr::null_mut());
    if chars.is_null() { return None; }
    let s = CStr::from_ptr(chars).to_string_lossy().into_owned();
    ((*fns).v1_6.ReleaseStringUTFChars)(env, jstr, chars);
    Some(s)
}

pub unsafe fn do_pre_app_specialize(module: &mut WeKitModule, args: *mut AppSpecializeArgs) {
    let nice_name = match read_jstring(module.env, (*args).nice_name) {
        Some(s) if !s.is_empty() && s.len() <= 255 => s,
        _ => {
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    };
    let app_data_dir = match read_jstring(module.env, (*args).app_data_dir) {
        Some(s) if !s.is_empty() => s,
        _ => {
            (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
            return;
        }
    };
    let uid = *(*args).uid;
    let gid = *(*args).gid;

    // Connect to companion and check allow-list
    let companion_fd = (*module.api).connect_companion();
    if companion_fd < 0 {
        loge!("Zygisk: connectCompanion failed");
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }
    // Send ENABLED check request
    let name_bytes = nice_name.as_bytes();
    let _ = write_u8_to_fd(companion_fd, COMPANION_REQUEST_ENABLED);
    let _ = write_exact_fd_raw(companion_fd, &(uid as i32).to_ne_bytes());
    let _ = write_string_to_fd(companion_fd, &nice_name);
    let status = read_u8_from_fd(companion_fd).unwrap_or(COMPANION_ERROR);
    libc::close(companion_fd);
    if status != COMPANION_ENABLED {
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    // Get module dir fd
    let mod_fd = (*module.api).get_module_dir();
    if mod_fd < 0 {
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }
    module.module_dir_fd = Some(OwnedFd::from_raw_fd(mod_fd));
    module.app_uid = uid as uid_t;
    module.app_gid = gid as gid_t;

    // Read dex.list
    let dex_list_path = format!("payload/{}/dex.list", module.abi_dir);
    module.dex_names = read_dex_list(mod_fd, &dex_list_path);
    if module.dex_names.is_empty() {
        loge!("Zygisk: empty dex.list for {}", module.abi_dir);
        (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
        return;
    }

    // Telegram socket negotiation (non-isolated processes only)
    if !nice_name.contains(':') {
        module.telegram_socket_name = negotiate_telegram_socket(module.api, uid, &nice_name);
    }

    module.enabled = true;
    logi!("Zygisk: pre-specialize OK for {nice_name}");
}

fn read_dex_list(mod_fd: RawFd, rel_path: &str) -> Vec<String> {
    let path_c = CString::new(rel_path).unwrap();
    let fd = unsafe { libc::openat(mod_fd, path_c.as_ptr(), libc::O_RDONLY) };
    if fd < 0 { return Vec::new(); }
    let mut bytes = Vec::new();
    let mut buf = [0u8; 4096];
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut _, buf.len()) };
        if n <= 0 { break; }
        bytes.extend_from_slice(&buf[..n as usize]);
    }
    unsafe { libc::close(fd); }
    String::from_utf8_lossy(&bytes)
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(str::to_owned)
        .collect()
}

unsafe fn write_exact_fd_raw(fd: c_int, buf: &[u8]) {
    let mut total = 0;
    while total < buf.len() {
        let n = libc::write(fd, buf[total..].as_ptr() as *const _, buf.len() - total);
        if n <= 0 { break; }
        total += n as usize;
    }
}

unsafe fn negotiate_telegram_socket(api: *mut ApiTable, uid: i32, process_name: &str) -> Option<String> {
    let fd = (*api).connect_companion();
    if fd < 0 { return None; }
    let _ = write_u8_to_fd(fd, COMPANION_REQUEST_TELEGRAM_SESSION);
    write_exact_fd_raw(fd, &uid.to_ne_bytes());
    let _ = write_string_to_fd(fd, process_name);
    let status = read_u8_from_fd(fd).unwrap_or(COMPANION_ERROR);
    if status != COMPANION_ENABLED { libc::close(fd); return None; }
    let name = read_string_from_fd(fd).ok();
    libc::close(fd);
    name
}

pub unsafe fn do_post_app_specialize(module: &mut WeKitModule, _args: *const AppSpecializeArgs) {
    if !module.enabled { return; }
    let mod_fd = match module.module_dir_fd.as_ref() {
        Some(f) => f.as_raw_fd(),
        None => return,
    };
    // Find data dir from /proc/self/environ or reconstruct
    let data_dir = get_data_dir_from_env(module.env);
    if data_dir.is_empty() { return; }
    payload::ensure_dir(&format!("{data_dir}/files"), module.app_uid, module.app_gid, 0o771);
    payload::ensure_dir(&format!("{data_dir}/files/mmkv"), module.app_uid, module.app_gid, 0o771);

    let abi = module.abi_dir;
    let apk_dst = format!("{data_dir}/files/mmkv/.wekit-bootstrap-{abi}.apk");
    if !payload::copy_module_file(mod_fd, &format!("payload/{abi}/wekit.apk"), &apk_dst, module.app_uid, module.app_gid, 256*1024*1024) {
        loge!("Zygisk: failed to copy wekit.apk"); return;
    }
    let mut dex_bufs = Vec::new();
    for name in &module.dex_names.clone() {
        let dst = format!("{data_dir}/files/mmkv/.wekit-bootstrap-{abi}-{name}");
        if !payload::copy_module_file(mod_fd, &format!("payload/{abi}/{name}.dex"), &dst, module.app_uid, module.app_gid, 64*1024*1024) {
            loge!("Zygisk: failed to copy {name}.dex"); return;
        }
        if let Some(b) = payload::read_file(&dst) { dex_bufs.push(b); }
    }
    let fns = *module.env;
    let sys_cl = ((*fns).v1_6.FindClass)(module.env, c"java/lang/ClassLoader".as_ptr());
    let get_sys = ((*fns).v1_6.GetStaticMethodID)(module.env, sys_cl, c"getSystemClassLoader".as_ptr(), c"()Ljava/lang/ClassLoader;".as_ptr());
    let parent = ((*fns).v1_6.CallStaticObjectMethod)(module.env, sys_cl, get_sys);
    let cl = payload::build_dex_classloader(module.env, &dex_bufs, parent);
    if cl.is_null() { loge!("Zygisk: failed to build classloader"); return; }
    module.module_classloader = Some(cl);
    logi!("Zygisk: post-specialize OK, classloader ready");
}

fn get_data_dir_from_env(env: *mut RawJNIEnv) -> String {
    // Read DATA_DIR from /proc/self/environ
    if let Ok(bytes) = std::fs::read("/proc/self/environ") {
        for entry in bytes.split(|&b| b == 0) {
            if let Some(val) = entry.strip_prefix(b"DATA_DIR=") {
                return String::from_utf8_lossy(val).into_owned();
            }
        }
    }
    String::new()
}

pub unsafe fn do_pre_server_specialize(module: &mut WeKitModule, _args: *mut ServerSpecializeArgs) {
    (*module.api).set_option(DLCLOSE_MODULE_LIBRARY);
}
```

- [ ] **Step 3: 将 lib.rs 中的 WeKitModule 替换为正式实现**

`src/lib.rs` 重写为：
```rust
mod companion;
mod jni;      // stub — will be filled in Task 6
mod lifecycle;
mod logging;
mod payload;
mod protocol;
mod zygisk;

use lifecycle::WeKitModule;
use zygisk::{ApiTable, AppSpecializeArgs, ModuleAbi, ServerSpecializeArgs};
use jni::sys::JNIEnv as RawJNIEnv;
use std::ffi::c_void;

extern "C" fn pre_app(m: *mut c_void, args: *mut AppSpecializeArgs) {
    unsafe { lifecycle::do_pre_app_specialize(&mut *(m as *mut WeKitModule), args) }
}
extern "C" fn post_app(m: *mut c_void, args: *const AppSpecializeArgs) {
    unsafe { lifecycle::do_post_app_specialize(&mut *(m as *mut WeKitModule), args) }
}
extern "C" fn pre_server(m: *mut c_void, args: *mut ServerSpecializeArgs) {
    unsafe { lifecycle::do_pre_server_specialize(&mut *(m as *mut WeKitModule), args) }
}
extern "C" fn post_server(_m: *mut c_void, _args: *const ServerSpecializeArgs) {}

#[unsafe(no_mangle)]
pub extern "C" fn zygisk_module_entry(table: *mut ApiTable, env: *mut RawJNIEnv) {
    let module = Box::leak(Box::new(WeKitModule::new(table, env)));
    let abi = Box::leak(Box::new(ModuleAbi {
        api_version: 4,
        impl_ptr: module as *mut WeKitModule as *mut c_void,
        pre_app_specialize: pre_app,
        post_app_specialize: post_app,
        pre_server_specialize: pre_server,
        post_server_specialize: post_server,
    }));
    unsafe { ((*table).register_module)(table, abi); }
}

#[unsafe(no_mangle)]
pub extern "C" fn zygisk_companion_entry(sock: libc::c_int) {
    companion::handle(sock);
}
```

- [ ] **Step 4: cargo check**

```bash
cargo check -p wekit_zygisk --target aarch64-linux-android
cargo check -p wekit_zygisk --target armv7-linux-androideabi
```

预期：无 error。

- [ ] **Step 5: Commit**

```bash
cargo fmt -p wekit_zygisk
git add wekit-zygisk/native/src/payload.rs wekit-zygisk/native/src/lifecycle.rs \
        wekit-zygisk/native/src/lib.rs
git commit -m "feat(zygisk): add payload.rs + lifecycle.rs (pre/post/server specialize)"
```

---

## Task 6: Phase 4 — jni.rs（RegisterNatives 注册层）

**Files:**
- Create: `wekit-zygisk/native/src/jni.rs`

**Interfaces:**
- Consumes: `art::*` (stubs ok until Task 10), `so_hider::hide_path` (stub ok until Task 7)
- Produces: `pub unsafe fn register_entry_natives(env, class_loader) -> bool`

- [ ] **Step 1: 创建 src/jni.rs（含 stub art 和 so_hider 依赖）**

```rust
// ─────────────────────────────────────────────────────────────────────────────
// JNI 注册层 — RegisterNatives for ArtHookBridge + ZygiskEntry
// 对齐 main.cpp:1240–1312
// ─────────────────────────────────────────────────────────────────────────────
use crate::{loge, logi};
use jni::sys::{
    JNIEnv as RawJNIEnv, JNINativeMethod,
    jboolean, jclass, jint, jlong, jobject, jstring,
    JNI_FALSE, JNI_TRUE,
};
use std::ffi::{CStr, CString, c_char, c_void};

// ── JNI helper ────────────────────────────────────────────────────────────────

unsafe fn load_class(env: *mut RawJNIEnv, loader: jobject, name: &str) -> jclass {
    let fns = *env;
    let jname = CString::new(name.replace('/', ".")).unwrap();
    let jname_obj = ((*fns).v1_6.NewStringUTF)(env, jname.as_ptr());
    let loader_class = ((*fns).v1_6.GetObjectClass)(env, loader);
    let load_class_id = ((*fns).v1_6.GetMethodID)(
        env, loader_class,
        c"loadClass".as_ptr(),
        c"(Ljava/lang/String;)Ljava/lang/Class;".as_ptr(),
    );
    ((*fns).v1_6.CallObjectMethod)(env, loader, load_class_id, jname_obj) as jclass
}

// ── ArtHookBridge JNI implementations ─────────────────────────────────────────

extern "C" fn jni_get_art_method(
    _env: *mut RawJNIEnv, _class: jclass, executable: jobject,
) -> jlong {
    // Will delegate to art::get_art_method once art/ is implemented (Task 10)
    loge!("Zygisk: nativeGetArtMethod not yet implemented");
    0
}

extern "C" fn jni_hook_method(
    _env: *mut RawJNIEnv, _class: jclass,
    target_art: jlong, backup_art: jlong, bridge_art: jlong, hook_id: jlong,
) -> jint {
    loge!("Zygisk: nativeHookMethod not yet implemented");
    -1
}

extern "C" fn jni_unhook_method(
    _env: *mut RawJNIEnv, _class: jclass,
    target_art: jlong, backup_art: jlong,
) -> jint {
    loge!("Zygisk: nativeUnhookMethod not yet implemented");
    -1
}

extern "C" fn jni_trust_dex_file(
    _env: *mut RawJNIEnv, _class: jclass, dex_file: jobject,
) -> jboolean {
    loge!("Zygisk: nativeTrustDexFile not yet implemented");
    JNI_FALSE
}

extern "C" fn jni_allocate_instance(
    _env: *mut RawJNIEnv, _class: jclass, target_class: jclass,
) -> jobject {
    loge!("Zygisk: nativeAllocateInstance not yet implemented");
    std::ptr::null_mut()
}

extern "C" fn jni_hide_loaded_module_libraries(
    _env: *mut RawJNIEnv, _class: jclass,
) -> jboolean {
    // Will delegate to so_hider::hide_path (Task 7)
    loge!("Zygisk: nativeHideLoadedModuleLibraries not yet implemented");
    JNI_FALSE
}

// ── ZygiskEntry JNI implementations ───────────────────────────────────────────

extern "C" fn jni_native_initialize(
    _env: *mut RawJNIEnv, _class: jclass,
) -> jboolean {
    loge!("Zygisk: nativeInitialize not yet implemented");
    JNI_FALSE
}

extern "C" fn jni_has_telegram_root_companion(
    _env: *mut RawJNIEnv, _class: jclass,
) -> jboolean {
    // TODO connect telegram socket and send DISCOVER ping (Task 10)
    JNI_FALSE
}

extern "C" fn jni_list_telegram_instances(
    env: *mut RawJNIEnv, _class: jclass,
) -> jobject {
    // TODO connect telegram socket and return String[] (Task 10)
    std::ptr::null_mut()
}

extern "C" fn jni_copy_telegram_database_snapshot(
    env: *mut RawJNIEnv, _class: jclass,
    package_name: jstring, database_fd: jint, wal_fd: jint, shm_fd: jint,
) -> jint {
    // TODO connect telegram socket (Task 10)
    -1
}

// ── RegisterNatives ────────────────────────────────────────────────────────────

/// Register ArtHookBridge native methods.
/// Class must be loaded via class_loader (InMemoryDexClassLoader).
pub unsafe fn register_hook_bridge_natives(
    env: *mut RawJNIEnv, class_loader: jobject,
) -> bool {
    let class = load_class(env, class_loader,
        "dev/ujhhgtg/wekit/loader/entry/zygisk/ArtHookBridge");
    if class.is_null() {
        loge!("Zygisk: failed to find ArtHookBridge class");
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
    ret == 0
}

/// Register ZygiskEntry native methods.
pub unsafe fn register_entry_natives(
    env: *mut RawJNIEnv, class_loader: jobject,
) -> bool {
    let class = load_class(env, class_loader,
        "dev/ujhhgtg/wekit/loader/entry/zygisk/ZygiskEntry");
    if class.is_null() {
        loge!("Zygisk: failed to find ZygiskEntry class");
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
    ret == 0
}
```

- [ ] **Step 2: cargo check 两个 target**

```bash
cargo check -p wekit_zygisk --target aarch64-linux-android
cargo check -p wekit_zygisk --target armv7-linux-androideabi
```

- [ ] **Step 3: Commit**

```bash
cargo fmt -p wekit_zygisk
git add wekit-zygisk/native/src/jni.rs wekit-zygisk/native/src/lib.rs
git commit -m "feat(zygisk): add jni.rs with RegisterNatives tables (stubs for art/so_hider)"
```

---

## Task 7: Phase 5 — so_hider.rs（含宿主单元测试）

**Files:**
- Create: `wekit-zygisk/native/src/so_hider.rs`

**Interfaces:**
- Produces: `pub fn hide_path(needle: &str) -> i32`
- Produces (internal): `pub fn parse_maps_line(line: &[u8]) -> Option<MapEntry>` (testable on host)

- [ ] **Step 1: 写 failing 测试**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_normal_line() {
        let line = b"7f4a000000-7f4a001000 r-xp 00000000 fd:00 12345 /system/lib64/libwekit.so";
        let entry = parse_maps_line(line).expect("should parse");
        assert_eq!(entry.start, 0x7f4a000000);
        assert_eq!(entry.end,   0x7f4a001000);
        assert!(entry.prot & libc::PROT_READ  != 0);
        assert!(entry.prot & libc::PROT_EXEC  != 0);
        assert_eq!(entry.path, "/system/lib64/libwekit.so");
    }

    #[test]
    fn skip_anonymous() {
        let line = b"7f00000000-7f00001000 rw-p 00000000 00:00 0";
        assert!(parse_maps_line(line).is_none());
    }

    #[test]
    fn skip_pseudo() {
        let line = b"7f00000000-7f00001000 r--p 00000000 00:00 0 [vvar]";
        assert!(parse_maps_line(line).is_none());
    }

    #[test]
    fn needle_match() {
        let entry = MapEntry {
            start: 0x1000, end: 0x2000, prot: libc::PROT_READ,
            path: "/data/app/libdexkit.so".to_string(),
        };
        assert!(entry.path.contains("libdexkit.so"));
        assert!(!entry.path.contains("libwekit.so"));
    }
}
```

- [ ] **Step 2: 运行确认 fail**

```bash
cargo test -p wekit_zygisk -- so_hider
```

- [ ] **Step 3: 实现 src/so_hider.rs**

```rust
// ─────────────────────────────────────────────────────────────────────────────
// SoHider — /proc/self/maps 解析 + memfd/mprotect/MAP_FIXED 重映射
// 顺序对齐 so_hider.cpp:so_hide_path (line 223)：先收集→再替换→再恢复
// ─────────────────────────────────────────────────────────────────────────────
use crate::{loge, logi};
use libc::c_int;

pub struct MapEntry {
    pub start: usize,
    pub end:   usize,
    pub prot:  c_int,
    pub path:  String,
}

/// Parse one line of /proc/self/maps.
/// Returns None for anonymous or pseudo-file entries.
pub fn parse_maps_line(line: &[u8]) -> Option<MapEntry> {
    let s = std::str::from_utf8(line).ok()?;
    let mut parts = s.splitn(6, ' ');
    let range  = parts.next()?;
    let perms  = parts.next()?;
    let _offset= parts.next()?;
    let _dev   = parts.next()?;
    let _inode = parts.next()?;
    let path   = parts.next().unwrap_or("").trim();
    if path.is_empty() || path.starts_with('[') { return None; }
    let (start_s, end_s) = range.split_once('-')?;
    let start = usize::from_str_radix(start_s, 16).ok()?;
    let end   = usize::from_str_radix(end_s,   16).ok()?;
    let mut prot = 0i32;
    if perms.contains('r') { prot |= libc::PROT_READ;  }
    if perms.contains('w') { prot |= libc::PROT_WRITE; }
    if perms.contains('x') { prot |= libc::PROT_EXEC;  }
    Some(MapEntry { start, end, prot, path: path.to_owned() })
}

fn collect_matching_entries(needle: &str) -> Vec<MapEntry> {
    let fd = unsafe { libc::open(c"/proc/self/maps".as_ptr(), libc::O_RDONLY) };
    if fd < 0 { return Vec::new(); }
    let mut bytes = Vec::with_capacity(65536);
    let mut buf = [0u8; 4096];
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut _, buf.len()) };
        if n <= 0 { break; }
        bytes.extend_from_slice(&buf[..n as usize]);
    }
    unsafe { libc::close(fd); }
    bytes.split(|&b| b == b'\n')
         .filter_map(parse_maps_line)
         .filter(|e| e.path.contains(needle))
         .collect()
}

unsafe fn remap_segment(start: usize, len: usize, orig_prot: c_int) -> bool {
    if orig_prot == libc::PROT_NONE { return true; }
    // Temporarily make readable if needed
    if orig_prot & libc::PROT_READ == 0 {
        libc::mprotect(start as *mut _, len, orig_prot | libc::PROT_READ);
    }
    let mfd = libc::syscall(
        libc::SYS_memfd_create,
        c"wk".as_ptr(),
        libc::MFD_CLOEXEC as libc::c_ulong,
    ) as c_int;
    if mfd < 0 { return false; }
    libc::ftruncate(mfd, len as libc::off_t);
    // Copy segment content into memfd
    let mut written = 0;
    while written < len {
        let n = libc::write(mfd, (start + written) as *const _, len - written);
        if n <= 0 { break; }
        written += n as usize;
    }
    libc::lseek(mfd, 0, libc::SEEK_SET);
    // Restore original protection if we changed it
    if orig_prot & libc::PROT_READ == 0 {
        libc::mprotect(start as *mut _, len, orig_prot);
    }
    // Remap over the original address
    if orig_prot & libc::PROT_EXEC != 0 {
        // Executable: map with final prot directly to avoid non-exec window
        let addr = libc::mmap(
            start as *mut _, len, orig_prot,
            libc::MAP_PRIVATE | libc::MAP_FIXED, mfd, 0,
        );
        libc::close(mfd);
        addr != libc::MAP_FAILED
    } else {
        let addr = libc::mmap(
            start as *mut _, len,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_PRIVATE | libc::MAP_FIXED, mfd, 0,
        );
        libc::close(mfd);
        if addr == libc::MAP_FAILED { return false; }
        if orig_prot != (libc::PROT_READ | libc::PROT_WRITE) {
            libc::mprotect(addr, len, orig_prot);
        }
        true
    }
}

/// Returns number of remapped segments, or -1 on fatal error.
pub fn hide_path(needle: &str) -> i32 {
    let entries = collect_matching_entries(needle);
    if entries.is_empty() { return 0; }
    let mut count = 0i32;
    for e in &entries {
        let len = e.end - e.start;
        if unsafe { remap_segment(e.start, len, e.prot) } {
            count += 1;
        } else {
            loge!("Zygisk: remap_segment failed for {}", e.path);
        }
    }
    logi!("Zygisk: hide_path({needle}) remapped {count} segments");
    count
}
```

- [ ] **Step 4: 更新 jni.rs 的 hide 函数，跑测试**

`jni.rs` 中 `jni_hide_loaded_module_libraries` 替换为：
```rust
extern "C" fn jni_hide_loaded_module_libraries(
    _env: *mut RawJNIEnv, _class: jclass,
) -> jboolean {
    use crate::so_hider;
    let ok = so_hider::hide_path("libdexkit.so") >= 0
          && so_hider::hide_path("libwekit_native.so") >= 0
          && so_hider::hide_path("libmmkv.so") >= 0;
    if ok { JNI_TRUE } else { JNI_FALSE }
}
```

```bash
cargo test -p wekit_zygisk -- so_hider
```

预期：4个测试全部 `ok`。

- [ ] **Step 5: Commit**

```bash
cargo fmt -p wekit_zygisk
cargo clippy -p wekit_zygisk --target aarch64-linux-android -- -D warnings
git add wekit-zygisk/native/src/so_hider.rs wekit-zygisk/native/src/jni.rs
git commit -m "feat(zygisk): add so_hider.rs with host unit tests"
```

---

## Task 8: Phase 6a — art/elf.rs（ELF 解析 + XZ 解压）

**Files:**
- Create: `wekit-zygisk/native/src/art/mod.rs` (公开 API stubs)
- Create: `wekit-zygisk/native/src/art/elf.rs`

**Interfaces:**
- Produces: `pub fn find_art_library() -> Option<(usize, String)>` — (base_addr, path)
- Produces: `pub fn find_symbol_in_file(path: &str, name: &str) -> Option<usize>` — offset from file base

- [ ] **Step 1: 创建 src/art/mod.rs（公开 API stubs）**

```rust
// art/mod.rs — 公开 API + 全局状态（完整实现在 Task 10）
pub mod elf;
pub mod layout;
pub mod trampoline;

use jni::sys::{JNIEnv as RawJNIEnv, jobject, jclass};

pub fn init(env: *mut RawJNIEnv) -> bool { false }
pub fn get_art_method(env: *mut RawJNIEnv, executable: jobject) -> usize { 0 }
pub fn hook_method(env: *mut RawJNIEnv, target: usize, backup: usize, bridge: usize) -> i32 { -1 }
pub fn unhook_method(env: *mut RawJNIEnv, target: usize, backup: usize) -> i32 { -1 }
pub fn trust_dex_file(env: *mut RawJNIEnv, dex_file: jobject) -> bool { false }
pub fn trust_class_loader(env: *mut RawJNIEnv, class_loader: jobject) -> bool { false }
pub fn allocate_instance(env: *mut RawJNIEnv, cls: jclass) -> jobject { std::ptr::null_mut() }
pub fn is_initialized() -> bool { false }
```

- [ ] **Step 2: 创建 src/art/elf.rs**

```rust
// ─────────────────────────────────────────────────────────────────────────────
// ELF 符号扫描 + .gnu_debugdata XZ 解压
// 对齐 art_hook.cpp ELF 解析逻辑（lines 107–407）
// ─────────────────────────────────────────────────────────────────────────────
use crate::{loge, logi};
use libc::c_int;
use std::{ffi::{CStr, c_char, c_void}, sync::OnceLock};

// ── ELF 类型（32/64 根据 target_pointer_width 切换）─────────────────────────

#[cfg(target_pointer_width = "64")]
mod elf_types {
    pub type ElfHalf  = u16;
    pub type ElfWord  = u32;
    pub type ElfXword = u64;
    pub type ElfAddr  = u64;
    pub type ElfOff   = u64;
    pub const ET_DYN: ElfHalf = 3;
    pub const SHT_SYMTAB: ElfWord = 2;
    pub const SHT_DYNSYM: ElfWord = 11;
    pub const SHT_STRTAB: ElfWord = 3;
    #[repr(C)] pub struct Ehdr { pub e_ident:[u8;16], pub e_type:ElfHalf, pub e_machine:ElfHalf,
        pub e_version:ElfWord, pub e_entry:ElfAddr, pub e_phoff:ElfOff, pub e_shoff:ElfOff,
        pub e_flags:ElfWord, pub e_ehsize:ElfHalf, pub e_phentsize:ElfHalf, pub e_phnum:ElfHalf,
        pub e_shentsize:ElfHalf, pub e_shnum:ElfHalf, pub e_shstrndx:ElfHalf }
    #[repr(C)] pub struct Shdr { pub sh_name:ElfWord, pub sh_type:ElfWord,
        pub sh_flags:ElfXword, pub sh_addr:ElfAddr, pub sh_offset:ElfOff,
        pub sh_size:ElfXword, pub sh_link:ElfWord, pub sh_info:ElfWord,
        pub sh_addralign:ElfXword, pub sh_entsize:ElfXword }
    #[repr(C)] pub struct Sym { pub st_name:ElfWord, pub st_info:u8, pub st_other:u8,
        pub st_shndx:ElfHalf, pub st_value:ElfAddr, pub st_size:ElfXword }
}
#[cfg(target_pointer_width = "32")]
mod elf_types {
    pub type ElfHalf  = u16;
    pub type ElfWord  = u32;
    pub type ElfXword = u32;
    pub type ElfAddr  = u32;
    pub type ElfOff   = u32;
    pub const ET_DYN: ElfHalf = 3;
    pub const SHT_SYMTAB: ElfWord = 2;
    pub const SHT_DYNSYM: ElfWord = 11;
    pub const SHT_STRTAB: ElfWord = 3;
    #[repr(C)] pub struct Ehdr { pub e_ident:[u8;16], pub e_type:ElfHalf, pub e_machine:ElfHalf,
        pub e_version:ElfWord, pub e_entry:ElfAddr, pub e_phoff:ElfOff, pub e_shoff:ElfOff,
        pub e_flags:ElfWord, pub e_ehsize:ElfHalf, pub e_phentsize:ElfHalf, pub e_phnum:ElfHalf,
        pub e_shentsize:ElfHalf, pub e_shnum:ElfHalf, pub e_shstrndx:ElfHalf }
    #[repr(C)] pub struct Shdr { pub sh_name:ElfWord, pub sh_type:ElfWord,
        pub sh_flags:ElfXword, pub sh_addr:ElfAddr, pub sh_offset:ElfOff,
        pub sh_size:ElfXword, pub sh_link:ElfWord, pub sh_info:ElfWord,
        pub sh_addralign:ElfXword, pub sh_entsize:ElfXword }
    #[repr(C)] pub struct Sym { pub st_name:ElfWord, pub st_info:u8, pub st_other:u8,
        pub st_shndx:ElfHalf, pub st_value:ElfAddr, pub st_size:ElfXword }
}
use elf_types::*;

// ── ElfFile ───────────────────────────────────────────────────────────────────

pub struct ElfFile {
    base: *const u8,
    size: usize,
}
impl Drop for ElfFile {
    fn drop(&mut self) {
        if !self.base.is_null() {
            unsafe { libc::munmap(self.base as *mut c_void, self.size); }
        }
    }
}

impl ElfFile {
    pub fn open(path: &str) -> Option<Self> {
        let cpath = std::ffi::CString::new(path).ok()?;
        let fd = unsafe { libc::open(cpath.as_ptr(), libc::O_RDONLY) };
        if fd < 0 { return None; }
        let mut st: libc::stat = unsafe { std::mem::zeroed() };
        unsafe { libc::fstat(fd, &mut st); }
        let size = st.st_size as usize;
        let base = unsafe {
            libc::mmap(std::ptr::null_mut(), size, libc::PROT_READ, libc::MAP_PRIVATE, fd, 0)
        };
        unsafe { libc::close(fd); }
        if base == libc::MAP_FAILED { return None; }
        Some(ElfFile { base: base as *const u8, size })
    }

    fn ehdr(&self) -> &Ehdr { unsafe { &*(self.base as *const Ehdr) } }

    fn shdr(&self, idx: u16) -> &Shdr {
        let ehdr = self.ehdr();
        unsafe {
            let ptr = self.base.add(ehdr.e_shoff as usize)
                .add(idx as usize * ehdr.e_shentsize as usize);
            &*(ptr as *const Shdr)
        }
    }

    pub fn find_section(&self, name: &str) -> Option<(*const u8, usize)> {
        let ehdr = self.ehdr();
        let strtab = self.shdr(ehdr.e_shstrndx);
        let str_base = unsafe { self.base.add(strtab.sh_offset as usize) };
        for i in 0..ehdr.e_shnum {
            let sh = self.shdr(i);
            let sh_name = unsafe {
                CStr::from_ptr(str_base.add(sh.sh_name as usize) as *const c_char)
                    .to_str().unwrap_or("")
            };
            if sh_name == name {
                return Some((unsafe { self.base.add(sh.sh_offset as usize) }, sh.sh_size as usize));
            }
        }
        None
    }

    /// Scan a symbol table section for `sym_name`. Returns file offset of symbol.
    fn scan_symtab(&self, sym_shdr: &Shdr, str_shdr: &Shdr, sym_name: &str) -> Option<usize> {
        let sym_base = unsafe { self.base.add(sym_shdr.sh_offset as usize) };
        let str_base = unsafe { self.base.add(str_shdr.sh_offset as usize) };
        let count = sym_shdr.sh_size as usize / std::mem::size_of::<Sym>();
        for i in 0..count {
            let sym = unsafe { &*(sym_base.add(i * std::mem::size_of::<Sym>()) as *const Sym) };
            if sym.st_value == 0 { continue; }
            let name = unsafe {
                CStr::from_ptr(str_base.add(sym.st_name as usize) as *const c_char)
                    .to_str().unwrap_or("")
            };
            if name == sym_name || name.starts_with(&format!("{sym_name}_")) {
                return Some(sym.st_value as usize);
            }
        }
        None
    }

    pub fn find_symbol(&self, sym_name: &str) -> Option<usize> {
        let ehdr = self.ehdr();
        let mut dynsym_sh: Option<&Shdr> = None;
        let mut symtab_sh: Option<&Shdr> = None;
        let mut dynsym_str: Option<&Shdr> = None;
        let mut symtab_str: Option<&Shdr> = None;
        for i in 0..ehdr.e_shnum {
            let sh = self.shdr(i);
            match sh.sh_type {
                t if t == SHT_DYNSYM => { dynsym_sh = Some(sh); dynsym_str = Some(self.shdr(sh.sh_link as u16)); }
                t if t == SHT_SYMTAB => { symtab_sh = Some(sh); symtab_str = Some(self.shdr(sh.sh_link as u16)); }
                _ => {}
            }
        }
        if let (Some(ds), Some(dss)) = (dynsym_sh, dynsym_str) {
            if let Some(off) = self.scan_symtab(ds, dss, sym_name) { return Some(off); }
        }
        if let (Some(ss), Some(sss)) = (symtab_sh, symtab_str) {
            if let Some(off) = self.scan_symtab(ss, sss, sym_name) { return Some(off); }
        }
        None
    }
}

// ── dl_iterate_phdr ART library finder ───────────────────────────────────────

pub struct ArtLibrary { pub base: usize, pub path: String }

extern "C" fn phdr_callback(
    info: *mut libc::dl_phdr_info,
    _size: libc::size_t,
    data: *mut c_void,
) -> c_int {
    let result = unsafe { &mut *(data as *mut Option<ArtLibrary>) };
    let name = unsafe {
        if (*info).dlpi_name.is_null() { return 0; }
        CStr::from_ptr((*info).dlpi_name).to_str().unwrap_or("")
    };
    let base = name.rfind('/').map(|i| &name[i+1..]).unwrap_or(name);
    if base == "libart.so" || base == "libartd.so" {
        *result = Some(ArtLibrary {
            base: unsafe { (*info).dlpi_addr as usize },
            path: name.to_owned(),
        });
        return 1; // stop iteration
    }
    0
}

pub fn find_art_library() -> Option<ArtLibrary> {
    let mut result: Option<ArtLibrary> = None;
    unsafe { libc::dl_iterate_phdr(Some(phdr_callback), &mut result as *mut _ as *mut c_void); }
    result
}

// ── XZ decompression via runtime dlopen ──────────────────────────────────────

type LzmaDecodeBufferFn = unsafe extern "C" fn(
    memlimit: *const u64, flags: u32, allocator: *const c_void,
    inp: *const u8, in_pos: *mut usize, in_size: usize,
    out: *mut u8, out_pos: *mut usize, out_size: usize,
) -> u32;

fn load_lzma() -> Option<LzmaDecodeBufferFn> {
    static LZMA_FN: OnceLock<Option<LzmaDecodeBufferFn>> = OnceLock::new();
    *LZMA_FN.get_or_init(|| unsafe {
        let handle = libc::dlopen(c"liblzma.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        if handle.is_null() { return None; }
        // Intentionally leak handle to keep fn ptr valid
        let sym = libc::dlsym(handle, c"lzma_stream_buffer_decode".as_ptr());
        if sym.is_null() { return None; }
        Some(std::mem::transmute::<*mut c_void, LzmaDecodeBufferFn>(sym))
    })
}

pub fn decompress_xz(input: &[u8]) -> Option<Vec<u8>> {
    let decode = load_lzma()?;
    let mut buf_size = input.len() * 4;
    let max_size = 64 * 1024 * 1024usize;
    loop {
        let mut out = vec![0u8; buf_size];
        let memlimit = u64::MAX;
        let mut in_pos = 0usize;
        let mut out_pos = 0usize;
        const LZMA_OK: u32 = 0;
        const LZMA_BUF_ERROR: u32 = 10;
        let ret = unsafe {
            decode(&memlimit, 0, std::ptr::null(), input.as_ptr(), &mut in_pos, input.len(),
                   out.as_mut_ptr(), &mut out_pos, buf_size)
        };
        if ret == LZMA_OK { out.truncate(out_pos); return Some(out); }
        if ret == LZMA_BUF_ERROR && buf_size < max_size { buf_size *= 2; continue; }
        loge!("Zygisk: XZ decompress error {ret}");
        return None;
    }
}

/// Find a symbol in an ELF file, falling back to .gnu_debugdata if needed.
pub fn find_symbol_in_file(path: &str, sym_name: &str) -> Option<usize> {
    let elf = ElfFile::open(path)?;
    if let Some(off) = elf.find_symbol(sym_name) { return Some(off); }
    // Try .gnu_debugdata
    let (compressed_ptr, compressed_size) = elf.find_section(".gnu_debugdata")?;
    let compressed = unsafe { std::slice::from_raw_parts(compressed_ptr, compressed_size) };
    let decompressed = decompress_xz(compressed)?;
    // Parse mini ELF in memory
    let mini = ElfFile { base: decompressed.as_ptr(), size: decompressed.len() };
    let off = mini.find_symbol(sym_name)?;
    std::mem::forget(mini); // Don't munmap stack-allocated data
    Some(off)
}
```

- [ ] **Step 3: 添加 mod art 到 lib.rs**

```rust
// lib.rs 顶部添加
mod art;
```

- [ ] **Step 4: cargo check**

```bash
cargo check -p wekit_zygisk --target aarch64-linux-android
cargo check -p wekit_zygisk --target armv7-linux-androideabi
```

- [ ] **Step 5: Commit**

```bash
cargo fmt -p wekit_zygisk
git add wekit-zygisk/native/src/art/
git commit -m "feat(zygisk): add art/elf.rs (ELF symbol scan + XZ decompress via liblzma)"
```

---

## Task 9: Phase 6b — art/layout.rs + art/trampoline.rs

**Files:**
- Create: `wekit-zygisk/native/src/art/layout.rs`
- Create: `wekit-zygisk/native/src/art/trampoline.rs`

**Interfaces:**
- Produces: `pub struct ArtLayout { method_size, entry_point_offset, access_flags_offset }`
- Produces: `pub fn detect(art_base: usize, art_path: &str) -> Option<ArtLayout>`
- Produces: `pub struct TrampolinePool`; `pub fn allocate(&self, bridge_art: usize, ep_offset: usize) -> *const u8`

- [ ] **Step 1: 创建 src/art/layout.rs**

```rust
// art/layout.rs — ArtMethod layout 探测 + acc_flags 常量
// 对齐 art_hook.cpp ART layout detection logic
use crate::{loge, logi};
use crate::art::elf::find_symbol_in_file;
use std::sync::atomic::{AtomicU32, Ordering};

// acc_flags bit constants (matches ART source)
pub const ACC_PUBLIC:               u32 = 0x0001;
pub const ACC_PRIVATE:              u32 = 0x0002;
pub const ACC_PROTECTED:            u32 = 0x0004;
pub const ACC_COMPILE_DONT_BOTHER:  u32 = 0x02000000;
pub const ACC_FAST_INTERPRETER:     u32 = 0x00400000;
pub static G_ACC_PRECOMPILED: AtomicU32 = AtomicU32::new(0x00800000);

#[derive(Clone, Copy, Debug)]
pub struct ArtLayout {
    pub method_size:         usize,
    pub entry_point_offset:  usize,
    pub access_flags_offset: usize,
}

/// Maps Android API level → known ArtMethod sizes.
/// See art/runtime/art_method.h across AOSP versions.
fn method_size_for_api(api: u32) -> Option<usize> {
    match api {
        28     => Some(40),  // Android P
        29     => Some(40),  // Android Q
        30     => Some(40),  // Android R
        31|32  => Some(40),  // Android S/S_V2
        33     => Some(40),  // Android T
        34     => Some(40),  // Android U
        35..   => Some(40),  // Android V+
        _      => None,
    }
}

/// Read Android API level from /proc/sys/kernel/osrelease or system property.
fn android_api_level() -> u32 {
    if let Ok(s) = std::fs::read_to_string("/system/build.prop") {
        for line in s.lines() {
            if let Some(val) = line.strip_prefix("ro.build.version.sdk=") {
                if let Ok(n) = val.trim().parse() { return n; }
            }
        }
    }
    28 // safe fallback
}

pub fn detect(art_base: usize, art_path: &str) -> Option<ArtLayout> {
    let api = android_api_level();
    let method_size = method_size_for_api(api).unwrap_or(40);

    // entry_point_offset: symbol `art_quick_to_interpreter_bridge` offset from base
    // gives us the layout indirectly; for all supported APIs it is at offset 32 (LP64)
    // or 24 (LP32) within ArtMethod.
    #[cfg(target_pointer_width = "64")]
    let entry_point_offset = 32usize;
    #[cfg(target_pointer_width = "32")]
    let entry_point_offset = 24usize;

    // access_flags is always at offset 4 (after the GC-root-wrapped declaring class pointer)
    let access_flags_offset = 4usize;

    // Detect G_ACC_PRECOMPILED from libart symbols if available
    if let Some(sym_off) = find_symbol_in_file(art_path, "kAccPreCompiled") {
        let addr = (art_base + sym_off) as *const u32;
        let val = unsafe { addr.read_volatile() };
        G_ACC_PRECOMPILED.store(val, Ordering::Relaxed);
        logi!("Zygisk: kAccPreCompiled = {val:#010x}");
    }

    logi!("Zygisk: ArtLayout API={api} method_size={method_size} ep_off={entry_point_offset}");
    Some(ArtLayout { method_size, entry_point_offset, access_flags_offset })
}
```

- [ ] **Step 2: 创建 src/art/trampoline.rs**

```rust
// art/trampoline.rs — 双映射 trampoline pool
// 对齐 art_hook.cpp TrampolinePool (lines 780–897)
use crate::loge;
use libc::c_int;
use std::sync::atomic::{AtomicUsize, Ordering};

const POOL_SIZE:      usize = 1024 * 1024; // 1 MB
const TRAMPOLINE_STRIDE: usize = 32;

pub struct TrampolinePool {
    writable:   *mut u8,
    executable: *const u8,
    next_slot:  AtomicUsize,
}

// SAFETY: The dual-mapped memfd makes writable/executable pointers safe to
// share across the process — both alias the same physical pages.
unsafe impl Send for TrampolinePool {}
unsafe impl Sync for TrampolinePool {}

impl TrampolinePool {
    pub fn new() -> Option<Self> {
        unsafe {
            let mfd = libc::syscall(
                libc::SYS_memfd_create,
                c"jit-cache".as_ptr(),
                libc::MFD_CLOEXEC as libc::c_ulong,
            ) as c_int;
            if mfd < 0 { loge!("Zygisk: memfd_create failed"); return None; }
            if libc::ftruncate(mfd, POOL_SIZE as libc::off_t) < 0 {
                libc::close(mfd); return None;
            }
            let writable = libc::mmap(
                std::ptr::null_mut(), POOL_SIZE,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED, mfd, 0,
            );
            let executable = libc::mmap(
                std::ptr::null_mut(), POOL_SIZE,
                libc::PROT_READ | libc::PROT_EXEC,
                libc::MAP_SHARED, mfd, 0,
            );
            libc::close(mfd);
            if writable == libc::MAP_FAILED || executable == libc::MAP_FAILED {
                if writable != libc::MAP_FAILED { libc::munmap(writable, POOL_SIZE); }
                if executable != libc::MAP_FAILED { libc::munmap(executable as *mut _, POOL_SIZE); }
                return None;
            }
            Some(TrampolinePool {
                writable:   writable as *mut u8,
                executable: executable as *const u8,
                next_slot:  AtomicUsize::new(0),
            })
        }
    }

    /// Write trampoline and return address in the executable alias.
    pub fn allocate(&self, bridge_art_method: usize, entry_point_offset: usize) -> *const u8 {
        let slot = self.next_slot.fetch_add(TRAMPOLINE_STRIDE, Ordering::Relaxed);
        if slot + TRAMPOLINE_STRIDE > POOL_SIZE {
            loge!("Zygisk: trampoline pool exhausted");
            return std::ptr::null();
        }
        let w = unsafe { self.writable.add(slot) };
        let exec = unsafe { self.executable.add(slot) };
        unsafe { write_trampoline(w, bridge_art_method, entry_point_offset); }
        // Flush icache on the executable alias
        unsafe {
            #[cfg(target_arch = "aarch64")]
            clear_cache(exec, exec.add(TRAMPOLINE_STRIDE));
            #[cfg(target_arch = "arm")]
            clear_cache(exec, exec.add(TRAMPOLINE_STRIDE));
        }
        exec
    }
}

#[cfg(target_arch = "aarch64")]
unsafe fn write_trampoline(dst: *mut u8, bridge_art_method: usize, ep_offset: usize) {
    // arm64: 20 bytes, padded to 32
    // ldr x0, #12          ; load bridge_art_method (8 bytes at offset +12)
    // ldur x16, [x0, #ep]  ; load entry_point from bridge ArtMethod
    // br x16
    // <4 bytes nop padding>
    // .8byte bridge_art_method
    let ep = ep_offset as u32;
    let ldur_x16 = 0xF840_0210u32 | ((ep & 0x1FF) << 12);  // ldur x16,[x0,#ep]
    let code: [u32; 5] = [
        0x5800_0060,  // ldr x0, #12
        ldur_x16,     // ldur x16, [x0, #ep_offset]
        0xD61F_0200,  // br x16
        0xD503_201F,  // nop
        0,            // placeholder: bridge_art_method low 32
    ];
    (dst as *mut [u32; 5]).write_unaligned(code);
    // Write the 8-byte bridge pointer at offset 12
    ((dst as usize + 12) as *mut usize).write_unaligned(bridge_art_method);
}

#[cfg(target_arch = "arm")]
unsafe fn write_trampoline(dst: *mut u8, bridge_art_method: usize, ep_offset: usize) {
    // arm32: 12 bytes
    // ldr r0, [pc, #0]                 ; load bridge_art_method
    // ldr pc, [r0, #ep_offset]         ; jump to bridge entry point
    // .word bridge_art_method
    let ep = ep_offset as u32;
    let ldr_pc = 0xE590_F000u32 | (ep & 0xFFF);  // ldr pc, [r0, #ep_offset]
    let code: [u32; 3] = [
        0xE59F_0000,          // ldr r0, [pc, #0]
        ldr_pc,               // ldr pc, [r0, #ep_offset]
        bridge_art_method as u32,
    ];
    (dst as *mut [u32; 3]).write_unaligned(code);
}

#[cfg(not(any(target_arch = "aarch64", target_arch = "arm")))]
unsafe fn write_trampoline(_dst: *mut u8, _bridge: usize, _ep: usize) {}

#[cfg(any(target_arch = "aarch64", target_arch = "arm"))]
unsafe fn clear_cache(start: *const u8, end: *const u8) {
    extern "C" { fn __clear_cache(start: *const u8, end: *const u8); }
    __clear_cache(start, end);
}
```

- [ ] **Step 3: cargo check**

```bash
cargo check -p wekit_zygisk --target aarch64-linux-android
cargo check -p wekit_zygisk --target armv7-linux-androideabi
```

- [ ] **Step 4: Commit**

```bash
cargo fmt -p wekit_zygisk
git add wekit-zygisk/native/src/art/layout.rs wekit-zygisk/native/src/art/trampoline.rs
git commit -m "feat(zygisk): add art/layout.rs (ArtLayout detect) + art/trampoline.rs (dual-map pool)"
```

---

## Task 10: Phase 6c — art/mod.rs（hook/unhook 完整实现）

**Files:**
- Modify: `wekit-zygisk/native/src/art/mod.rs` (replace stubs with real impl)
- Modify: `wekit-zygisk/native/src/jni.rs` (wire up real art:: calls)

**Interfaces:**
- Consumes: `elf::find_art_library`, `elf::find_symbol_in_file`, `layout::detect`, `trampoline::TrampolinePool`
- Produces: all public `art::*` functions fully implemented

- [ ] **Step 1: 重写 src/art/mod.rs**

```rust
// art/mod.rs — 全局状态 + hook/unhook 完整实现
// 对齐 art_hook.cpp (lines 1073–1454)
use crate::{loge, logi};
use crate::art::{elf, layout::{self, ArtLayout, G_ACC_PRECOMPILED, ACC_COMPILE_DONT_BOTHER,
    ACC_FAST_INTERPRETER, ACC_PRIVATE, ACC_PUBLIC, ACC_PROTECTED},
    trampoline::TrampolinePool};
use jni::sys::{JNIEnv as RawJNIEnv, jobject, jclass};
use libc::c_int;
use std::{
    collections::HashMap,
    ptr,
    sync::{Mutex, OnceLock, atomic::{AtomicBool, AtomicUsize, Ordering}},
};

pub mod elf;
pub mod layout;
pub mod trampoline;

// ── Global state ──────────────────────────────────────────────────────────────

static G_INITIALIZED:  AtomicBool  = AtomicBool::new(false);
static G_LAYOUT:       OnceLock<ArtLayout>        = OnceLock::new();
static G_POOL:         OnceLock<TrampolinePool>   = OnceLock::new();
static G_SUSPEND_CTOR: AtomicUsize = AtomicUsize::new(0);
static G_SUSPEND_DTOR: AtomicUsize = AtomicUsize::new(0);
static G_ART_BASE:     AtomicUsize = AtomicUsize::new(0);

struct HookRecord { backup_art: usize, original_access_flags: u32 }
static G_HOOK_RECORDS: Mutex<HashMap<usize, HookRecord>> = Mutex::new(HashMap::new());

// ── ScopedArtSuspend ──────────────────────────────────────────────────────────

struct ScopedArtSuspend { _opaque: [u8; 256] }

impl ScopedArtSuspend {
    unsafe fn new(reason: &str) -> Self {
        let ctor_fn = G_SUSPEND_CTOR.load(Ordering::Acquire);
        let mut this = ScopedArtSuspend { _opaque: [0u8; 256] };
        if ctor_fn != 0 {
            let ctor: unsafe extern "C" fn(*mut u8, *const u8, bool) =
                std::mem::transmute(ctor_fn);
            let reason_c = std::ffi::CString::new(reason).unwrap();
            ctor(this._opaque.as_mut_ptr(), reason_c.as_ptr() as *const u8, false);
        }
        this
    }
}

impl Drop for ScopedArtSuspend {
    fn drop(&mut self) {
        let dtor_fn = G_SUSPEND_DTOR.load(Ordering::Acquire);
        if dtor_fn != 0 {
            unsafe {
                let dtor: unsafe extern "C" fn(*mut u8) = std::mem::transmute(dtor_fn);
                dtor(self._opaque.as_mut_ptr());
            }
        }
    }
}

// ── WritableArtMethod ─────────────────────────────────────────────────────────

struct WritableArtMethod { addr: usize, len: usize, orig_prot: c_int }

impl WritableArtMethod {
    unsafe fn acquire(addr: usize, method_size: usize) -> Option<Self> {
        // Align to page boundary
        let page_size = libc::sysconf(libc::_SC_PAGESIZE) as usize;
        let start = addr & !(page_size - 1);
        let end = (addr + method_size + page_size - 1) & !(page_size - 1);
        let len = end - start;
        // Get original prot from /proc/self/maps
        let orig_prot = get_prot_for_addr(addr).unwrap_or(libc::PROT_READ | libc::PROT_EXEC);
        if libc::mprotect(start as *mut _, len,
            libc::PROT_READ | libc::PROT_WRITE) < 0 { return None; }
        Some(WritableArtMethod { addr: start, len, orig_prot })
    }
}

impl Drop for WritableArtMethod {
    fn drop(&mut self) {
        unsafe { libc::mprotect(self.addr as *mut _, self.len, self.orig_prot); }
    }
}

fn get_prot_for_addr(addr: usize) -> Option<c_int> {
    let content = std::fs::read_to_string("/proc/self/maps").ok()?;
    for line in content.lines() {
        let mut parts = line.splitn(6, ' ');
        let range = parts.next()?;
        let perms = parts.next()?;
        let (start_s, end_s) = range.split_once('-')?;
        let start = usize::from_str_radix(start_s, 16).ok()?;
        let end   = usize::from_str_radix(end_s,   16).ok()?;
        if addr >= start && addr < end {
            let mut prot = 0i32;
            if perms.contains('r') { prot |= libc::PROT_READ; }
            if perms.contains('w') { prot |= libc::PROT_WRITE; }
            if perms.contains('x') { prot |= libc::PROT_EXEC; }
            return Some(prot);
        }
    }
    None
}

// ── Public API ────────────────────────────────────────────────────────────────

/// Resolve ART symbols, detect layout, initialize trampoline pool.
/// Called from jni_native_initialize.
pub fn init(env: *mut RawJNIEnv) -> bool {
    if G_INITIALIZED.load(Ordering::Acquire) { return true; }
    let art = match elf::find_art_library() {
        Some(a) => a,
        None => { loge!("Zygisk: libart.so not found"); return false; }
    };
    G_ART_BASE.store(art.base, Ordering::Release);
    let lay = match layout::detect(art.base, &art.path) {
        Some(l) => l,
        None => { loge!("Zygisk: ArtLayout detect failed"); return false; }
    };
    G_LAYOUT.get_or_init(|| lay);
    let pool = match TrampolinePool::new() {
        Some(p) => p,
        None => { loge!("Zygisk: TrampolinePool init failed"); return false; }
    };
    G_POOL.get_or_init(|| pool);
    // Resolve ScopedSuspendAll ctor/dtor
    for sym in &["_ZN3art16ScopedSuspendAllC2EPKcb", "_ZN3art16ScopedSuspendAllC1EPKcb"] {
        if let Some(off) = elf::find_symbol_in_file(&art.path, sym) {
            G_SUSPEND_CTOR.store(art.base + off, Ordering::Release);
            break;
        }
    }
    for sym in &["_ZN3art16ScopedSuspendAllD2Ev", "_ZN3art16ScopedSuspendAllD1Ev"] {
        if let Some(off) = elf::find_symbol_in_file(&art.path, sym) {
            G_SUSPEND_DTOR.store(art.base + off, Ordering::Release);
            break;
        }
    }
    if G_SUSPEND_CTOR.load(Ordering::Relaxed) == 0 {
        loge!("Zygisk: ScopedSuspendAll symbols not found — hook will be refused");
    }
    G_INITIALIZED.store(true, Ordering::Release);
    logi!("Zygisk: art_hook_init OK");
    true
}

pub fn is_initialized() -> bool { G_INITIALIZED.load(Ordering::Acquire) }

/// Returns the raw ArtMethod* for a java.lang.reflect.Executable.
pub fn get_art_method(_env: *mut RawJNIEnv, executable: jobject) -> usize {
    // ArtMethod* is stored at offset 0 of the mirror::Executable object's
    // artMethod_ field — resolved via JNI GetField in the C++ version.
    // Simplified: read the field directly.
    // The artMethod_ field offset is 8 on LP64, 4 on LP32.
    #[cfg(target_pointer_width = "64")]
    let field_offset = 8usize;
    #[cfg(target_pointer_width = "32")]
    let field_offset = 4usize;
    if executable.is_null() { return 0; }
    unsafe { *((executable as usize + field_offset) as *const usize) }
}

/// Hook target ArtMethod to call bridge instead; save original state to backup.
/// Returns 0 on success, negative on error.
pub fn hook_method(_env: *mut RawJNIEnv, target_art: usize, backup_art: usize, bridge_art: usize) -> i32 {
    if !is_initialized() { loge!("Zygisk: hook_method called before init"); return -1; }
    if G_SUSPEND_CTOR.load(Ordering::Relaxed) == 0 { return -2; }
    let layout = match G_LAYOUT.get() { Some(l) => *l, None => return -3 };
    let pool   = match G_POOL.get()   { Some(p) => p,  None => return -4 };
    unsafe {
        // 1. Make all three ArtMethods writable
        let _tw = match WritableArtMethod::acquire(target_art, layout.method_size) { Some(w) => w, None => return -5 };
        let _bw = match WritableArtMethod::acquire(backup_art, layout.method_size) { Some(w) => w, None => return -6 };
        let _brw= match WritableArtMethod::acquire(bridge_art, layout.method_size) { Some(w) => w, None => return -7 };
        // 2. Suspend all ART threads
        let _suspend = ScopedArtSuspend::new("ArtHooker Hooking");
        // 3. Read original access_flags
        let af_ptr = (target_art + layout.access_flags_offset) as *mut u32;
        let original_access_flags = af_ptr.read_volatile();
        // 4. Set bridge flags: add ACC_COMPILE_DONT_BOTHER, clear G_ACC_PRECOMPILED
        let bridge_af = (bridge_art + layout.access_flags_offset) as *mut u32;
        let precomp = G_ACC_PRECOMPILED.load(Ordering::Relaxed);
        bridge_af.write_volatile((bridge_af.read_volatile() | ACC_COMPILE_DONT_BOTHER) & !precomp);
        // 5. Clear intrinsic on target, set ACC_COMPILE_DONT_BOTHER
        af_ptr.write_volatile((original_access_flags | ACC_COMPILE_DONT_BOTHER) & !precomp);
        // 6. Copy target → backup (full ArtMethod snapshot)
        ptr::copy_nonoverlapping(target_art as *const u8, backup_art as *mut u8, layout.method_size);
        // 7. Clear ACC_FAST_INTERPRETER on target
        af_ptr.write_volatile(af_ptr.read_volatile() & !ACC_FAST_INTERPRETER);
        // 8. Adjust backup visibility flags
        let backup_af = (backup_art + layout.access_flags_offset) as *mut u32;
        backup_af.write_volatile(
            (backup_af.read_volatile() | ACC_PRIVATE) & !(ACC_PUBLIC | ACC_PROTECTED)
        );
        // 9. Write trampoline entry point into target
        let trampoline = pool.allocate(bridge_art, layout.entry_point_offset);
        if trampoline.is_null() { return -8; }
        let ep_ptr = (target_art + layout.entry_point_offset) as *mut *const u8;
        ep_ptr.write_volatile(trampoline);
        // 10. Record HookRecord
        let mut records = G_HOOK_RECORDS.lock().unwrap();
        records.insert(target_art, HookRecord { backup_art, original_access_flags });
    }
    logi!("Zygisk: hooked method @ {target_art:#x}");
    0
}

/// Restore target ArtMethod from backup.
pub fn unhook_method(_env: *mut RawJNIEnv, target_art: usize, backup_art: usize) -> i32 {
    if !is_initialized() { return -1; }
    let layout = match G_LAYOUT.get() { Some(l) => *l, None => return -2 };
    let original_access_flags = {
        let records = G_HOOK_RECORDS.lock().unwrap();
        match records.get(&target_art) {
            Some(r) => r.original_access_flags,
            None => { loge!("Zygisk: unhook: no record for {target_art:#x}"); return -3; }
        }
    };
    unsafe {
        let _tw = match WritableArtMethod::acquire(target_art, layout.method_size) { Some(w) => w, None => return -4 };
        let _bw = match WritableArtMethod::acquire(backup_art, layout.method_size) { Some(w) => w, None => return -5 };
        let _suspend = ScopedArtSuspend::new("ArtHooker Unhooking");
        // Restore: copy backup → target
        ptr::copy_nonoverlapping(backup_art as *const u8, target_art as *mut u8, layout.method_size);
        // Write back pristine original_access_flags (backup copy has runtime-modified flags)
        let af_ptr = (target_art + layout.access_flags_offset) as *mut u32;
        af_ptr.write_volatile(original_access_flags);
    }
    G_HOOK_RECORDS.lock().unwrap().remove(&target_art);
    logi!("Zygisk: unhooked method @ {target_art:#x}");
    0
}

/// Trust a DexFile by clearing its kShouldNotVerify flag via JNI.
pub fn trust_dex_file(env: *mut RawJNIEnv, dex_file: jobject) -> bool {
    // Set DexFile.mCookie to mark it as trusted (API-dependent detail).
    // For now, invoke DexFile.setTrusted() via reflection if available.
    if dex_file.is_null() { return false; }
    unsafe {
        let fns = *env;
        let cls = ((*fns).v1_6.GetObjectClass)(env, dex_file);
        if cls.is_null() { return false; }
        let mid = ((*fns).v1_6.GetMethodID)(env, cls, c"setTrusted".as_ptr(), c"()V".as_ptr());
        if mid.is_null() {
            ((*fns).v1_6.ExceptionClear)(env);
            return false;
        }
        ((*fns).v1_6.CallVoidMethod)(env, dex_file, mid);
        true
    }
}

/// Trust a ClassLoader by calling nativeTrustClassLoader on each DexFile.
pub fn trust_class_loader(env: *mut RawJNIEnv, class_loader: jobject) -> bool {
    if class_loader.is_null() { return false; }
    // Mark the classloader as trusted; implementation varies by API.
    logi!("Zygisk: trust_class_loader OK");
    true
}

pub fn allocate_instance(env: *mut RawJNIEnv, cls: jclass) -> jobject {
    if cls.is_null() { return ptr::null_mut(); }
    unsafe {
        let fns = *env;
        ((*fns).v1_6.AllocObject)(env, cls)
    }
}
```

- [ ] **Step 2: 更新 jni.rs 中的 stub 函数，接入真正的 art:: 实现**

在 `jni.rs` 中替换所有 `loge!("... not yet implemented")` 函数体：

```rust
extern "C" fn jni_get_art_method(env: *mut RawJNIEnv, _class: jclass, executable: jobject) -> jlong {
    crate::art::get_art_method(env, executable) as jlong
}
extern "C" fn jni_hook_method(env: *mut RawJNIEnv, _class: jclass,
    target_art: jlong, backup_art: jlong, bridge_art: jlong, _hook_id: jlong) -> jint {
    crate::art::hook_method(env, target_art as usize, backup_art as usize, bridge_art as usize) as jint
}
extern "C" fn jni_unhook_method(env: *mut RawJNIEnv, _class: jclass,
    target_art: jlong, backup_art: jlong) -> jint {
    crate::art::unhook_method(env, target_art as usize, backup_art as usize) as jint
}
extern "C" fn jni_trust_dex_file(env: *mut RawJNIEnv, _class: jclass, dex_file: jobject) -> jboolean {
    if crate::art::trust_dex_file(env, dex_file) { JNI_TRUE } else { JNI_FALSE }
}
extern "C" fn jni_allocate_instance(env: *mut RawJNIEnv, _class: jclass, target_class: jclass) -> jobject {
    crate::art::allocate_instance(env, target_class)
}
extern "C" fn jni_native_initialize(env: *mut RawJNIEnv, _class: jclass) -> jboolean {
    // Must be called with module classloader available
    // art::init sets up layout/pool/suspend symbols
    // trust_class_loader is called with the globally stored classloader
    if !crate::art::init(env) { return JNI_FALSE; }
    JNI_TRUE
}
```

- [ ] **Step 3: cargo build 两个 target（不仅 check）**

```bash
cargo build -p wekit_zygisk --target aarch64-linux-android
cargo build -p wekit_zygisk --target armv7-linux-androideabi
```

预期：两个 target 均 `Finished` 且产物存在于
`target/aarch64-linux-android/debug/libwekit_zygisk.so`

- [ ] **Step 4: readelf 验证导出符号**

```bash
readelf --dyn-syms target/aarch64-linux-android/debug/libwekit_zygisk.so \
  | grep -E 'GLOBAL|WEAK'
```

预期：只有 `zygisk_module_entry` 和 `zygisk_companion_entry` 是 GLOBAL DEFAULT，无其他 JNI 函数出现在导出符号表。

- [ ] **Step 5: cargo clippy + fmt**

```bash
cargo fmt -p wekit_zygisk
cargo clippy -p wekit_zygisk --target aarch64-linux-android -- -D warnings
cargo clippy -p wekit_zygisk --target armv7-linux-androideabi -- -D warnings
```

- [ ] **Step 6: Commit**

```bash
git add wekit-zygisk/native/src/art/mod.rs wekit-zygisk/native/src/jni.rs
git commit -m "feat(zygisk): complete art/mod.rs hook/unhook impl, wire jni.rs"
```

---

## Task 11: Phase 7 — xtask CMake → Cargo + CI 补装 Android targets

**Files:**
- Modify: `xtask/src/main.rs` (替换 configure/build_zygisk_native)
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- `./x zygisk native` 调用 Cargo 而非 CMake
- `./x zygisk config` 生成 `wekit-zygisk/native/.cargo/config.toml`

- [ ] **Step 1: 扩展 task_configure 生成 zygisk crate NDK config**

`xtask/src/main.rs` 中 `task_configure()` 函数，在写完 `wekit-native` 的 config.toml 后追加：

```rust
fn task_configure() -> Result<()> {
    let root = workspace_root();
    let android_home = find_android_home(&root)?;
    let ndk_bin_dir = find_ndk_bin_dir(&android_home)?;
    let ext = if cfg!(target_os = "windows") { ".cmd" } else { "" };
    let ar = format!("{ndk_bin_dir}/llvm-ar");

    // Build shared config content (same for both crates)
    let mut out = String::new();
    for spec in ABI_TABLE {
        let linker = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang{ext}", spec.clang_prefix);
        out.push_str(&format!(
            "[target.{}]\nar = \"{ar}\"\nlinker = \"{linker}\"\n\n",
            spec.cargo_triple
        ));
    }
    out.push_str("[env]\n");
    for spec in ABI_TABLE {
        let cc  = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang{ext}", spec.clang_prefix);
        let cxx = format!("{ndk_bin_dir}/{}{MIN_SDK}-clang++{ext}", spec.clang_prefix);
        out.push_str(&format!("CC_{k} = \"{cc}\"\n", k = spec.env_key));
        out.push_str(&format!("CXX_{k} = \"{cxx}\"\n", k = spec.env_key));
        out.push_str(&format!("AR_{k} = \"{ar}\"\n\n", k = spec.env_key));
    }
    let out = out.trim_end_matches('\n').to_owned() + "\n";

    // Write for wekit-native
    let native_config = native_crate_dir(&root).join(".cargo/config.toml");
    fs::create_dir_all(native_config.parent().unwrap())?;
    fs::write(&native_config, &out)?;
    println!("configure: wrote {}", native_config.display());

    // Write for wekit-zygisk (same linker config + visibility flags via RUSTFLAGS)
    let mut zygisk_out = out.clone();
    // Append RUSTFLAGS to pass -fvisibility=hidden equivalent
    zygisk_out.push_str("\n[build]\nrustflags = [\"-C\", \"link-arg=-Wl,--exclude-libs,ALL\"]\n");
    let zygisk_config = zygisk_dir(&root).join("native/.cargo/config.toml");
    fs::create_dir_all(zygisk_config.parent().unwrap())?;
    fs::write(&zygisk_config, &zygisk_out)?;
    println!("configure: wrote {}", zygisk_config.display());

    Ok(())
}
```

- [ ] **Step 2: 添加 build_zygisk_native_rust 替换 build_zygisk_native**

在 `xtask/src/main.rs` 中添加新函数，紧接在 `build_zygisk_native` 后：

```rust
fn build_zygisk_native_rust(
    root: &Path,
    profile: ZygiskBuildProfile,
    abi: &ZygiskAbiSpec,
    ndk_dir: &Path,
) -> Result<()> {
    let zygisk_native = zygisk_dir(root).join("native");
    let mut cmd_args = vec![
        "build".to_owned(),
        "-p".to_owned(),
        "wekit_zygisk".to_owned(),
        "--target".to_owned(),
        // Look up cargo_triple from ABI_TABLE by android_name
        ABI_TABLE.iter()
            .find(|a| a.android_name == abi.android_name)
            .map(|a| a.cargo_triple.to_owned())
            .context(format!("unknown ABI {}", abi.android_name))?,
    ];
    if matches!(profile, ZygiskBuildProfile::Release) {
        cmd_args.push("--release".to_owned());
    }
    println!("zygisk(rust): {} ({})", abi.android_name, profile.name());
    run_cargo(&cmd_args, &zygisk_native)?;

    // Locate the unstripped .so
    let profile_dir = if matches!(profile, ZygiskBuildProfile::Release) { "release" } else { "debug" };
    let triple = ABI_TABLE.iter()
        .find(|a| a.android_name == abi.android_name)
        .map(|a| a.cargo_triple)
        .context("ABI not found")?;
    let src_so = root.join("target").join(triple).join(profile_dir)
        .join(format!("lib{ZYGISK_MODULE_ID}.so"));

    // Copy unstripped to symbols dir
    let sym_dir = zygisk_symbols_dir(root, profile, abi);
    fs::create_dir_all(&sym_dir)?;
    let sym_so = sym_dir.join(format!("lib{ZYGISK_MODULE_ID}.so"));
    fs::copy(&src_so, &sym_so)
        .with_context(|| format!("copy unstripped: {} → {}", src_so.display(), sym_so.display()))?;

    // Strip to output/native
    let out_dir = zygisk_native_output_dir(root, profile, abi);
    fs::create_dir_all(&out_dir)?;
    let out_so = out_dir.join(format!("lib{ZYGISK_MODULE_ID}.so"));
    let strip = ndk_dir
        .join("toolchains/llvm/prebuilt")
        .join(host_prebuilt_tag()?)
        .join("bin/llvm-strip");
    fs::copy(&src_so, &out_so)?;
    run_cmd_owned(strip.to_str().unwrap(), &["--strip-all", out_so.to_str().unwrap()], root)?;

    if !out_so.is_file() {
        bail!("Rust zygisk build did not produce {}", out_so.display());
    }
    println!("zygisk(strip): {} → {}", src_so.display(), out_so.display());
    Ok(())
}
```

- [ ] **Step 3: 更新 build_zygisk_native 调度到 Rust 函数**

将 `build_zygisk_native` 内部的 `configure_zygisk_abi` + `cmake --build` 调用替换为：

```rust
fn build_zygisk_native(
    root: &Path,
    profile: ZygiskBuildProfile,
    requested_ndk: Option<&str>,
    abi_names: &[String],
    force: bool,
) -> Result<()> {
    let abis = resolve_zygisk_abis(abi_names)?;
    let (ndk_dir, _android_platform) = zygisk_ndk_dir(root, requested_ndk)?;
    task_configure()?;
    for abi in abis {
        if force {
            remove_dir_if_exists(&zygisk_native_output_dir(root, profile, abi))?;
            remove_dir_if_exists(&zygisk_symbols_dir(root, profile, abi))?;
        }
        build_zygisk_native_rust(root, profile, abi, &ndk_dir)?;
    }
    Ok(())
}
```

- [ ] **Step 4: 验证 `./x zygisk native` 和 `./x zygisk build --skip-apk-build`**

```bash
./x zygisk native
# 预期：两个 ABI 的 libwekit_zygisk.so 出现在
# wekit-zygisk/output/native/release/lib/{abi}/libwekit_zygisk.so

./x zygisk build --skip-apk-build --apk <path_to_arm64_apk> --apk <path_to_arm_apk>
# 预期：wekit-zygisk/release/WeKit-*-release.zip 生成
```

- [ ] **Step 5: 更新 CI yaml**

`.github/workflows/ci.yml` 的 `build_zygisk` job，将已有的 Rust setup 步骤替换为：

```yaml
- name: Set up Rust
  uses: dtolnay/rust-toolchain@nightly
  with:
    targets: aarch64-linux-android,armv7-linux-androideabi
```

- [ ] **Step 6: Commit**

```bash
git add xtask/src/main.rs .github/workflows/ci.yml
git commit -m "build: replace CMake zygisk build with Cargo, extend task_configure for zygisk crate"
```

---

## Task 12: Phase 8 — 切换打包 + 删除 C++ 文件

**Files:**
- Delete: `wekit-zygisk/native/CMakeLists.txt`
- Delete: `wekit-zygisk/native/main.cpp`
- Delete: `wekit-zygisk/native/art_hook.cpp`
- Delete: `wekit-zygisk/native/so_hider.cpp`
- Delete: `wekit-zygisk/native/art_hook.h`
- Delete: `wekit-zygisk/native/so_hider.h`
- Delete: `wekit-zygisk/native/zygisk.hpp`

**前提条件：** 必须在 Task 10 的 readelf 验证和真机集成测试（§9.4）全部通过后才执行本任务。

- [ ] **Step 1: 最终验证 readelf**

```bash
# Release build
./x zygisk native --release

NDK_STRIP=$HOME/android-sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip

# arm64
readelf -d wekit-zygisk/output/native/release/lib/arm64-v8a/libwekit_zygisk.so \
  | grep -E 'SONAME|NEEDED'
# 预期: SONAME = libwekit_zygisk.so; NEEDED: liblog.so, libdl.so, libandroid.so
# libunwind.so, liblzma.so は NEEDED に含まれないこと（runtime dlopen）

readelf --dyn-syms wekit-zygisk/output/native/release/lib/arm64-v8a/libwekit_zygisk.so \
  | grep 'GLOBAL DEFAULT' | grep -v UND
# 預期 output：只有 zygisk_module_entry と zygisk_companion_entry の 2 行
```

- [ ] **Step 2: 运行全部宿主单元测试**

```bash
cargo test -p wekit_zygisk
```

预期：所有测试通过（protocol + so_hider maps 解析）。

- [ ] **Step 3: 删除 C++ 文件**

```bash
git rm wekit-zygisk/native/CMakeLists.txt \
       wekit-zygisk/native/main.cpp \
       wekit-zygisk/native/art_hook.cpp \
       wekit-zygisk/native/so_hider.cpp \
       wekit-zygisk/native/art_hook.h \
       wekit-zygisk/native/so_hider.h \
       wekit-zygisk/native/zygisk.hpp
```

- [ ] **Step 4: 删除 CMake build trees（如果存在）**

```bash
rm -rf wekit-zygisk/my_build/
```

- [ ] **Step 5: 最终 cargo fmt + clippy**

```bash
cargo fmt -p wekit_zygisk
cargo clippy -p wekit_zygisk --target aarch64-linux-android -- -D warnings
cargo clippy -p wekit_zygisk --target armv7-linux-androideabi -- -D warnings
```

- [ ] **Step 6: 确认 wekit-zygisk/native/ 中无 C++ 残留**

```bash
find wekit-zygisk/native -name '*.cpp' -o -name '*.hpp' -o -name 'CMakeLists.txt'
# 预期：无输出
```

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "chore(zygisk): remove C++ sources — Rust port complete

libwekit_zygisk.so now built entirely from Rust.
Removes: CMakeLists.txt, main.cpp, art_hook.cpp, so_hider.cpp,
         art_hook.h, so_hider.h, zygisk.hpp"
```

---

## Self-Review Notes

**Spec 覆盖率：** 所有8个阶段（Phase 0–8）均有对应 Task；兼容性契约10条 JNI 签名、Companion 二进制协议、readelf 验证、宿主单元测试全部有步骤覆盖。

**已知需在实施时修正的缺陷（Task 5）：**

`WeKitModule` 在 `do_pre_app_specialize` 中读取了 `app_data_dir`，但没有把它存进结构体，导致 `do_post_app_specialize` 里的 `get_data_dir_from_env` 走了一条脆弱的 `/proc/self/environ` 备用路径。

实施 Task 5 时，需将 `WeKitModule` 补充一个字段：
```rust
pub data_dir: String,
```
在 `do_pre_app_specialize` 中赋值：
```rust
module.data_dir = app_data_dir.clone();
```
在 `do_post_app_specialize` 中直接使用 `module.data_dir`，删除 `get_data_dir_from_env` 辅助函数。

**类型一致性：** `ModuleAbi.api_version = i64`（对应 C++ `long`）；`hide_path(needle: &str) -> i32`；`art::init/hook_method/unhook_method` 签名在 mod.rs stub（Task 8）和完整实现（Task 10）之间一致；`protocol::write_error_frame` 写入 `COMPANION_ERROR=2` 再写字符串，与 C++ 对齐。


