# wekit-zygisk/native 迁移至 Rust 设计文档

**日期**：2026-07-24  
**状态**：已确认，待实施  
**方案**：A — 完全 Rust 移植，零 C++ 残留

---

## 目录

1. [兼容性契约](#1-兼容性契约)
2. [Crate 结构与模块职责](#2-crate-结构与模块职责)
3. [Zygisk ABI 层 (zygisk.rs)](#3-zygisk-abi-层-zygiskrs)
4. [生命周期、Companion 与协议](#4-生命周期companion-与协议)
5. [JNI 注册层 (jni.rs)](#5-jni-注册层-jnirs)
6. [SoHider (so_hider.rs)](#6-sohider-so_hiderrs)
7. [ART Hook 模块 (art/)](#7-art-hook-模块-art)
8. [构建系统变更](#8-构建系统变更)
9. [验证门槛](#9-验证门槛)
10. [实施阶段](#10-实施阶段)

---

## 1. 兼容性契约

迁移的"冻结基线"——Rust 实现必须逐条对齐，Kotlin 侧不作任何修改。

### 1.1 二进制接口

- **输出文件名**：`libwekit_zygisk.so`（Cargo `[lib] name = "wekit_zygisk"`）
- **ZIP 路径**：`lib/arm64-v8a/libwekit_zygisk.so`、`lib/armeabi-v7a/libwekit_zygisk.so`
- **ELF 目标**：Android API 28，支持 arm64-v8a 和 armeabi-v7a
- **导出符号**（仅两个，其余全部 hidden）：
  ```
  void zygisk_module_entry(api_table*, JNIEnv*)
  void zygisk_companion_entry(int sock)
  ```

### 1.2 JNI 契约（RegisterNatives，非 name-mangled 导出）

| 类 | Java 方法 | JNI 签名 |
|----|-----------|----------|
| ArtHookBridge | nativeGetArtMethod | `(Ljava/lang/reflect/Executable;)J` |
| ArtHookBridge | nativeHookMethod | `(JJJJ)I` |
| ArtHookBridge | nativeUnhookMethod | `(JJ)I` |
| ArtHookBridge | nativeTrustDexFile | `(Ldalvik/system/DexFile;)Z` |
| ArtHookBridge | nativeAllocateInstance | `(Ljava/lang/Class;)Ljava/lang/Object;` |
| ArtHookBridge | nativeHideLoadedModuleLibraries | `()Z` |
| ZygiskEntry | nativeInitialize | `()Z` |
| ZygiskEntry | nativeHasTelegramRootCompanion | `()Z` |
| ZygiskEntry | nativeListTelegramInstances | `()[Ljava/lang/String;` |
| ZygiskEntry | nativeCopyTelegramDatabaseSnapshot | `(Ljava/lang/String;III)I` |

注：`nativeTrustClassLoader` 不在注册表，仅在 Rust 内部调用。

### 1.3 Companion 二进制协议（版本 0）

- **请求头**：`[u8 request_type][i32 uid][u16 name_len][u8*name_len process_name]`
- **状态码**：`DISABLED=0, ENABLED=1, ERROR=2`
- **请求类型**：`0x01` ENABLED check，`0x02` Telegram session
- **Socket 名格式**：`\0wekit-tg-{uid}-{nonce:08x}`（abstract namespace）
- **Telegram 子协议**：`DISCOVER=0x01`，`COPY_DATABASE=0x02`
- **错误帧**：`[0x01][u16 len][msg bytes]`

长度限制、状态码和失败关闭行为逐条对齐 `main.cpp:45–211`。

### 1.4 日志关键字（保持不变）

- 标签：`WeKit`，前缀：`Zygisk:`

---

## 2. Crate 结构与模块职责

```
wekit-zygisk/native/
├── Cargo.toml               — cdylib, name="wekit_zygisk", edition=2024
└── src/
    ├── lib.rs               — 两个 Zygisk 导出函数
    ├── zygisk.rs            — #[repr(C)] ABI 定义：ApiTable/ModuleAbi/AppSpecializeArgs/等
    ├── lifecycle.rs         — WeKitModule 结构体 + 三个回调实现 + allow-list 检查
    ├── companion.rs         — companion_handler：allow-list、双 fork、abstract socket worker
    ├── protocol.rs          — 纯 Rust 编解码：read/write 帧、常量、TelegramOp（可宿主测试）
    ├── payload.rs           — APK/DEX 复制（原子 rename）、fchown、InMemoryDexClassLoader 构建
    ├── jni.rs               — 两张 RegisterNatives 表 + 全部 JNI 函数实现
    ├── logging.rs           — __android_log_write 绑定 + logi!/logw!/loge! 宏
    ├── so_hider.rs          — /proc/self/maps 解析 + memfd/mprotect/MAP_FIXED 重映射
    └── art/
        ├── mod.rs           — 公开 API + 全局状态
        ├── elf.rs           — ELF32/64 符号扫描 + .gnu_debugdata XZ 解压
        ├── layout.rs        — ArtMethod 大小/offset 探测 + acc_flags 常量
        └── trampoline.rs    — 双映射 trampoline pool，arm64/arm32 用 cfg(target_arch) 门控
```

### 依赖原则

- `libc`、`jni`（仅 `jni::sys`）：必需
- `logging.rs` 不引入 `log`/`android_logger`，与 wekit-native 完全同风格
- 不引入 async runtime；companion worker 用 `libc::fork` + blocking I/O
- XZ 解压：runtime `libc::dlopen("liblzma.so")` + `dlsym`，不添加纯 Rust lzma crate
- `panic = "abort"` 继承自 workspace `[profile.release]`，无需逐函数 `catch_unwind`

---

## 3. Zygisk ABI 层 (zygisk.rs)

### 核心思路

`zygisk.hpp` 的 C++ 虚函数表和模板宏，在 Rust 端全部用 `#[repr(C)]` 函数指针结构体还原。`WeKitModule` 分配在堆上，指针通过 `impl_ptr: *mut c_void` 穿进回调，对齐 C++ `module_abi.impl` 语义。

### 关键类型定义

```rust
/// Zygisk 运行时传入的 vtable；只使用 register_module，其余槽位保留偏移
#[repr(C)]
pub struct ApiTable {
    pub register_module: unsafe extern "C" fn(*mut ApiTable, *mut ModuleAbi, *mut RawJNIEnv),
    _reserved: [*mut c_void; 14],
}

/// 我们填写并交给运行时的模块描述符
#[repr(C)]
pub struct ModuleAbi {
    pub api_version:           u32,          // = 4
    pub impl_ptr:              *mut c_void,  // *mut WeKitModule
    pub pre_app_specialize:    unsafe extern "C" fn(*mut c_void, *mut AppSpecializeArgs),
    pub post_app_specialize:   unsafe extern "C" fn(*mut c_void, *const AppSpecializeArgs),
    pub pre_server_specialize: unsafe extern "C" fn(*mut c_void, *mut ServerSpecializeArgs),
    pub post_server_specialize:unsafe extern "C" fn(*mut c_void, *const ServerSpecializeArgs),
}
```

`AppSpecializeArgs` / `ServerSpecializeArgs` 按 `zygisk.hpp` 字段顺序逐字段还原，可选字段保持 `*mut T`（空指针 = 不存在）。

### zygisk_module_entry 实现流程

1. `Box::leak(Box::new(WeKitModule::new(api, env)))` → 稳定的 `*mut WeKitModule`
2. 以该指针作 `impl_ptr`，填写四个 `extern "C" fn` 回调（各自将 `impl_ptr` cast 回 `*mut WeKitModule`）
3. `Box::leak(Box::new(ModuleAbi { ... }))` → `*mut ModuleAbi`
4. `((*table).register_module)(table, abi, env)`

### zygisk_companion_entry 实现

直接转发：`companion::handle(sock)`，无需分配。

---

## 4. 生命周期、Companion 与协议

### 4.1 lifecycle.rs — WeKitModule

```rust
struct WeKitModule {
    api: *mut ApiTable,
    env: *mut RawJNIEnv,
    // preAppSpecialize 填写
    module_dir_fd:        Option<OwnedFd>,    // Drop 自动关闭
    app_uid:              libc::uid_t,
    app_gid:              libc::gid_t,
    abi_dir:              &'static str,       // "arm64" 或 "arm"
    dex_names:            Vec<String>,
    telegram_socket_name: Option<String>,
    enabled:              bool,
    // postAppSpecialize 填写
    module_classloader:   Option<jobject>,    // GlobalRef
}
```

**pre_app_specialize**：读 `nice_name` / `app_data_dir`；超长（>255 字节）或为空时调 `DLCLOSE_MODULE_LIBRARY`；连接 companion 请求 allow-list 状态，DISABLED/ERROR 时 dlclose；检查当前 ABI；`getModuleDir()` 保存 `OwnedFd`；读 `payload/{abi}/dex.list`；非 isolated 进程（process name 不含 `:`）时协商 Telegram socket；`enabled = true`。

**post_app_specialize**：创建 `{data_dir}/files/` 和 `{data_dir}/files/mmkv/`；复制 `payload/{abi}/wekit.apk` 和各 DEX（`copy_module_file`，原子 rename + fchown + fsync）；`payload::build_dex_classloader` 构建 `InMemoryDexClassLoader`；调用 `ZygiskEntry.init(processName, dataDir, apkPath)`；`register_entry_natives()`；保存 classloader GlobalRef。

**pre_server_specialize**：立即调 `DLCLOSE_MODULE_LIBRARY`，不注入 system_server。

### 4.2 companion.rs

`companion_handler(sock: c_int)` 步骤：

1. `protocol::read_header(sock)` → `(request_type, uid, process_name)`
2. `is_enabled_target(uid, &process_name)` — 读 `/data/adb/wekit/injection-targets.tsv`；只接受 `com.tencent.mm` 前缀、`enabled == "1"`、process name 精确匹配或 `package_name:` 前缀匹配
3. 分发：
   - `0x01`：写回单字节状态（`DISABLED/ENABLED/ERROR`）
   - `0x02`：创建 abstract socket（`\0wekit-tg-{uid}-{nonce:08x}`，nonce 来自 `/dev/urandom`）→ 双 fork → 孙子进程运行 `telegram_worker(server_fd)`；父进程等中间子进程退出，写回 `ENABLED + u16 name_len + name bytes`

`telegram_worker(server_fd)` 循环 `libc::accept`，处理：
- `DISCOVER(0x01)`：返回 `[OK][u16 count][for each: u16len + pkg bytes]`
- `COPY_DATABASE(0x02)`：返回 `[OK]`，再对 database/wal/shm 各发 `[u64 size][bytes]`（size=0 表示文件不存在）


### 4.3 protocol.rs（宿主可测）

```rust
pub const COMPANION_REQUEST_ENABLED:          u8 = 0x01;
pub const COMPANION_REQUEST_TELEGRAM_SESSION: u8 = 0x02;
pub const COMPANION_DISABLED: u8 = 0;
pub const COMPANION_ENABLED:  u8 = 1;
pub const COMPANION_ERROR:    u8 = 2;

pub fn read_u8_from_fd(fd: c_int)              -> Result<u8,      IoError>;
pub fn read_u16_from_fd(fd: c_int)             -> Result<u16,     IoError>;
pub fn read_u64_from_fd(fd: c_int)             -> Result<u64,     IoError>;
pub fn read_bytes_from_fd(fd: c_int, n: usize) -> Result<Vec<u8>, IoError>;
pub fn read_string_from_fd(fd: c_int)          -> Result<String,  IoError>; // u16 len + bytes
pub fn write_string_to_fd(fd: c_int, s: &str)  -> Result<(), IoError>;
pub fn write_u8_to_fd(fd: c_int, v: u8)        -> Result<(), IoError>;
pub fn write_error_frame(fd: c_int, msg: &str) -> Result<(), IoError>;
```

长度限制、状态码和失败关闭行为逐条对齐 `main.cpp:45–211`。单元测试用 `libc::socketpair(AF_UNIX, SOCK_STREAM, 0)` 构造合法/畸形帧验证编解码对称性与长度边界。

### 4.4 payload.rs

```rust
pub fn copy_module_file(
    module_dir_fd: RawFd, src: &str, dst: &str,
    uid: uid_t, gid: gid_t, max_bytes: u64,
) -> Result<(), Error>;   // temp 文件 + atomic rename + fchown + fsync

pub fn read_copied_file(path: &str)  -> Result<Vec<u8>, Error>;
pub fn ensure_dir(path: &str, uid: uid_t, gid: gid_t, mode: u32) -> Result<(), Error>;

pub fn build_dex_classloader(
    env: *mut RawJNIEnv,
    dex_buffers: &[Vec<u8>],
    parent_loader: jobject,
) -> Result<jobject, Error>;   // InMemoryDexClassLoader via JNI

pub fn call_zygisk_entry_init(
    env: *mut RawJNIEnv, class_loader: jobject,
    process_name: &str, data_dir: &str, apk_path: &str,
) -> Result<(), Error>;
```

DEX 顺序：`dex.list` 必须包含 `classes.dex, classes2.dex, ...`；`payload.rs` 按序号验证顺序（对齐 C++ `dex_name_order()`）。

---

## 5. JNI 注册层 (jni.rs)

### 注册策略

所有 JNI 函数实现为**普通** `extern "C" fn`（非 `#[no_mangle]`，不导出）；函数指针收进 `JNINativeMethod` 数组传给 `RegisterNatives`。

**关键**：目标类通过 `InMemoryDexClassLoader` 加载，`FindClass` 找不到，必须经由 classloader 调 `loadClass("dev.ujhhgtg.wekit.loader.entry.zygisk.ArtHookBridge")` 拿到 `jclass` 后再注册。

```rust
pub unsafe fn register_hook_bridge_natives(
    env: *mut RawJNIEnv, class_loader: jobject,
) -> bool;   // ArtHookBridge 6 个方法

pub unsafe fn register_entry_natives(
    env: *mut RawJNIEnv, class_loader: jobject,
) -> bool;   // ZygiskEntry 4 个方法
```

### JNI 函数代理表

| JNI 函数 | 代理目标 |
|----------|---------|
| `nativeGetArtMethod` | `art::get_art_method(env, executable)` → `jlong` |
| `nativeHookMethod` | `art::hook_method(env, target, backup, bridge)` → `jint` |
| `nativeUnhookMethod` | `art::unhook_method(env, target, backup)` → `jint` |
| `nativeTrustDexFile` | `art::trust_dex_file(env, dex_file)` → `jboolean` |
| `nativeAllocateInstance` | `art::allocate_instance(env, class)` → `jobject` |
| `nativeHideLoadedModuleLibraries` | `so_hider::hide_path` × 3（libdexkit/libwekit_native/libmmkv）→ `jboolean` |
| `nativeInitialize` | `art::init(env)` → `art::trust_class_loader(cl)` → `register_hook_bridge_natives(cl)` → `jboolean` |
| `nativeHasTelegramRootCompanion` | 连接 telegram socket，探测 companion 响应 → `jboolean` |
| `nativeListTelegramInstances` | 发 DISCOVER → `String[]` |
| `nativeCopyTelegramDatabaseSnapshot` | 发 COPY_DATABASE，写入三个 fd → `jint` |

JNI 边界不 panic（`panic = "abort"` 保底）；Java 异常用原始 `ExceptionCheck`/`ExceptionClear` 清理；失败返回 `JNI_FALSE` / `-1` / `null`。

---

## 6. SoHider (so_hider.rs)

与 C++ 完全对齐的三步结构：**先收集映射 → 再逐段替换 → 再恢复保护**。

```rust
pub fn hide_path(needle: &str) -> i32   // 重映射段数；-1 为致命错误
```

### parse_maps

用 `libc::open("/proc/self/maps")` + 手动逐行字节解析；跳过匿名段（path 为空或以 `[` 开头）；收集 `path.contains(needle)` 的条目为 `Vec<MapEntry>`；**先把所有条目收集完、关闭 maps fd，再进入替换循环**（与 C++ 顺序完全一致）。

### remap_segment（全部 unsafe，集中在一个函数内）

1. `orig_prot == PROT_NONE` → skip
2. 若不可读：`mprotect(start, len, orig_prot | PROT_READ)`
3. `syscall(SYS_memfd_create, c"wk".as_ptr(), MFD_CLOEXEC)` → `mfd`
4. `ftruncate(mfd, len)`；write loop 复制段内容；恢复原始保护
5. 可执行段：`mmap(start, len, orig_prot, MAP_PRIVATE|MAP_FIXED, mfd, 0)`（直接写最终保护，避免代码段短暂不可执行）
6. 非可执行段：`mmap(..., PROT_READ|PROT_WRITE, ...)` 再 `mprotect(..., orig_prot)`
7. `close(mfd)`

### 宿主单元测试

`parse_maps_line(line: &[u8]) -> Option<MapEntry>` 接受字节切片，用合成的 maps 内容验证路径过滤和 prot 解析；`remap_segment` 本身不做宿主测试。


---

## 7. ART Hook 模块 (art/)

最高风险阶段，独立完成。**纯真机 CI，无宿主单元测试。**

### art/elf.rs

手动 mmap ELF 解析（不依赖 libelf）：

```rust
struct ElfFile { ptr: *const u8, size: usize }   // Drop: libc::munmap
```

- `ElfFile::open(path)` → `open + fstat + mmap(PROT_READ, MAP_PRIVATE)`
- `find_symbol(name)` → 先扫 `.dynsym`，再扫 `.symtab`；支持精确匹配和前缀匹配
- `find_section(name)` → `(*const u8, usize)`
- `find_art_library()` → `dl_iterate_phdr` 匹配 `libart.so` / `libartd.so`

ELF32/64 结构体用 `#[cfg(target_pointer_width)]` 门控，均 `#[repr(C)]`。

**.gnu_debugdata / XZ 路径**（`dynsym`/`symtab` 均找不到时触发）：

- `load_lzma()` → `OnceLock<Option<LzmaDecodeFn>>`：runtime `libc::dlopen(c"liblzma.so", RTLD_NOW|RTLD_LOCAL)` + `dlsym`；handle 故意泄漏保持函数指针存活
- `decompress_xz(input)` → 调 `lzma_stream_buffer_decode`；`LZMA_BUF_ERROR` 时最多加倍缓冲区至 64 MB
- 解压后得到迷你 ELF image，再走一次 `find_symbol`

### art/layout.rs

```rust
pub struct ArtLayout {
    pub method_size:          usize,
    pub entry_point_offset:   usize,
    pub access_flags_offset:  usize,
}
static G_ACC_PRECOMPILED: AtomicU32 = AtomicU32::new(0);
```

`ArtLayout::detect(env)` 通过已知 API level vs ArtMethod 大小映射表探测；记录 `entry_point_offset` 和 `access_flags_offset`。

### art/trampoline.rs

```rust
struct TrampolinePool {
    writable:   *mut u8,        // mmap PROT_READ|PROT_WRITE, MAP_SHARED
    executable: *const u8,     // mmap PROT_READ|PROT_EXEC, MAP_SHARED
    next_slot:  AtomicUsize,   // stride 32 字节
}
```

memfd `"jit-cache"` 1 MB，两次 `mmap` 共享同一文件 — 通过 writable 别名写代码、executable 别名执行，**完全不调 `mprotect(PROT_EXEC)`**。

**arm64（20 字节，补齐 32）**（`#[cfg(target_arch = "aarch64")]`）：
```
ldr x0, #12                         ; 加载 bridge_art_method 地址
ldur x16, [x0, #{entry_point_offset}]; 加载 bridge 的 quick entry point
br x16
<4 字节 padding>
.8byte bridge_art_method
```

**arm32（12 字节）**（`#[cfg(target_arch = "arm")]`）：
```
ldr r0, [pc, #0]
ldr pc, [r0, #{entry_point_offset}]
.word bridge_art_method
```

写入后调 `__clear_cache` 等价操作刷指令缓存。

### art/mod.rs — 全局状态与核心流程

```rust
static G_INITIALIZED:   AtomicBool;
static G_LAYOUT:        OnceLock<ArtLayout>;
static G_POOL:          OnceLock<TrampolinePool>;
static G_HOOK_RECORDS:  Mutex<HashMap<usize, HookRecord>>;  // key = target_art
static G_SUSPEND_CTOR:  AtomicUsize;   // ScopedSuspendAll ctor fn ptr
static G_SUSPEND_DTOR:  AtomicUsize;   // ScopedSuspendAll dtor fn ptr

struct HookRecord { backup_art: usize, original_access_flags: u32 }
```

`ScopedArtSuspend` RAII struct：构造时调 `G_SUSPEND_CTOR`，`Drop` 时调 `G_SUSPEND_DTOR`；`panic = "abort"` 保证析构一定发生。

`WritableArtMethod::acquire(addr)` → 读 `/proc/self/maps` 找含该地址的段，`mprotect(PROT_READ|PROT_WRITE)`；`Drop` 恢复原始 flags。

**art_hook_method（11 步，严格对齐 art_hook.cpp:1197–1305）**：

1. acquire 三个 `WritableArtMethod`
2. `ScopedArtSuspend::new()`
3. 保存 `original_access_flags`
4. 设置 bridge flags（ADD `ACC_COMPILE_DONT_BOTHER`，CLEAR `g_acc_precompiled`）
5. clear intrinsic，设置 target `ACC_COMPILE_DONT_BOTHER`
6. `ptr::copy_nonoverlapping(target → backup, method_size)`
7. clear `ACC_FAST_INTERPRETER` on target
8. 调整 backup visibility flags
9. 写 trampoline entrypoint 到 target `entry_point_offset`
10. 插入 `HookRecord`
11. `WritableArtMethod` Drop 自动恢复页保护

**art_unhook_method**：取 `HookRecord` → acquire → `ScopedArtSuspend` → `copy_nonoverlapping(backup → target)` → 写回 `original_access_flags`（不用 backup 里的值，与 C++ 一致）→ 删 `HookRecord`。


---

## 8. 构建系统变更

### 8.1 workspace Cargo.toml

```toml
[workspace]
members = [
    "app/src/main/rust/wekit-native/",
    "wekit-zygisk/native/",            # 新增
    "xtask",
]

[profile.release.package.wekit_zygisk]
strip = "none"    # xtask 负责手动剥离，保留 unstripped 供 symbols ZIP
# wekit-native 继承 workspace strip = true，不受影响
```

### 8.2 wekit-zygisk/native/Cargo.toml

```toml
[package]
name    = "wekit_zygisk"
version = "0.1.0"
edition = "2024"

[lib]
name       = "wekit_zygisk"   # → libwekit_zygisk.so
crate-type = ["cdylib"]

[dependencies]
libc = "0.2"
jni  = { version = "0.22", default-features = false }
```

### 8.3 xtask 改造

**task_configure 扩展**：在生成 `wekit-native/.cargo/config.toml` 的同时，生成 `wekit-zygisk/native/.cargo/config.toml`（相同 NDK linker 设置；追加 `RUSTFLAGS = "-C link-arg=-fvisibility=hidden -C link-arg=-fno-exceptions"`）。`ZygiskCmd::Config` 改为调此逻辑，不再调 CMake configure。

**build_zygisk_native_rust(root, profile, abi)** 替换原 `configure_zygisk_abi` + `build_zygisk_native`：

```
1. cargo build -p wekit_zygisk --target {abi.cargo_triple} [--release]
2. src = target/{triple}/{profile}/libwekit_zygisk.so
3. copy → wekit-zygisk/output/unstripped/{profile}/{abi}/libwekit_zygisk.so
4. {ndk}/llvm-strip --strip-all src → wekit-zygisk/output/native/{profile}/lib/{abi}/libwekit_zygisk.so
5. 验证目标文件存在（原 build_zygisk_native 行 904 的检查）
```

`package_zygisk_module` 中所有 `libwekit.so` 引用改为 `libwekit_zygisk.so`；`@SONAME@` 展开值更新。`ZygiskCmd::Clean` 清理 `wekit-zygisk/output/` 及 Cargo target 对应包。

### 8.4 CI (.github/workflows/ci.yml)

`build_zygisk` job 新增：

```yaml
- uses: dtolnay/rust-toolchain@nightly
  with:
    targets: aarch64-linux-android,armv7-linux-androideabi
```

（`build` job 已有此步骤；`build_zygisk` 目前不安装 Android targets，必须补上。）

### 8.5 迁移期并存策略

迁移期间保留 C++ 基准构建，同步将 `-DMODULE_NAME` 改为 `wekit_zygisk`，使两套产物文件名一致。切换 `package_zygisk_module` 默认输入至 Rust 产物；最终验收通过后删除 `CMakeLists.txt`、`main.cpp`、`art_hook.cpp`、`so_hider.cpp`、`art_hook.h`、`so_hider.h`、`zygisk.hpp`；更新 README 中 CMake/clangd 说明。

---

## 9. 验证门槛

### 9.1 每阶段持续验证（宿主）

```
cargo fmt --check -p wekit_zygisk
cargo clippy -p wekit_zygisk --target aarch64-linux-android -- -D warnings
cargo clippy -p wekit_zygisk --target armv7-linux-androideabi -- -D warnings
cargo test -p wekit_zygisk                    # protocol/so_hider maps 解析单元测试
cargo check -p wekit_zygisk --target aarch64-linux-android
cargo check -p wekit_zygisk --target armv7-linux-androideabi
```

### 9.2 产物静态验证（readelf）

| 项目 | 期望值 |
|------|--------|
| ELF class | ELF64（arm64）/ ELF32（arm） |
| OS/ABI | Android |
| `NEEDED` | `liblog.so`, `libdl.so`, `libandroid.so`（不含 `liblzma.so`，runtime dlopen） |
| SONAME | `libwekit_zygisk.so` |
| 导出符号（仅两个） | `zygisk_module_entry`, `zygisk_companion_entry` |
| JNI 函数 | **不在**导出符号表，通过 RegisterNatives 注册 |

### 9.3 构建命令验证

```
./x zygisk native
./x zygisk build --skip-apk-build
./x zygisk build --save-symbols    # symbols ZIP 正确生成
CI build_zygisk job 全部通过
```

### 9.4 真机集成测试

- 未启用目标：卸载后模块不崩溃，WeChat 正常启动
- 主/子进程注入，payload 加载成功
- 静态方法 / 实例方法 / 构造器 hook 与 unhook
- `nativeTrustDexFile` DEX trust
- Maps 隐藏：`libdexkit.so`、`libwekit_native.so`、`libmmkv.so` 从 `/proc/self/maps` 消失
- Telegram 多实例发现（DISCOVER 协议）
- WAL/SHM 快照（COPY_DATABASE 协议）

### 9.5 最终清理检查

`wekit-zygisk/native/` 中不存在任何 `.cpp`、`.hpp`、`CMakeLists.txt`；Kotlin `external` 函数签名不变；模块安装布局（`lib/{abi}/libwekit_zygisk.so`）不变。

---

## 10. 实施阶段

| 阶段 | 内容 | 关键风险 |
|------|------|---------|
| 0 | 冻结 C++ 基准：readelf 快照、导出符号列表、CMake 改名 wekit_zygisk | 低 |
| 1 | 建立 Rust crate + Zygisk ABI 定义（zygisk.rs，两个导出函数骨架） | 中（#[repr(C)] 布局对齐） |
| 2 | 迁移 protocol.rs + companion.rs（含宿主单元测试） | 低 |
| 3 | 迁移 lifecycle.rs + payload.rs（preApp/postApp/preServer 三个回调） | 中 |
| 4 | 迁移 jni.rs（RegisterNatives 两张表 + 10 个函数实现） | 低 |
| 5 | 迁移 so_hider.rs（含宿主 maps 解析单元测试） | 低 |
| 6 | 迁移 art/（ELF → layout → trampoline → mod.rs hook/unhook），单独完成 | **高** |
| 7 | 切换 xtask + CI（CMake → Cargo，补装 Android targets） | 中 |
| 8 | 切换打包默认输入至 Rust 产物；完整验证门槛通过后删除 C++ 文件 | 低 |

