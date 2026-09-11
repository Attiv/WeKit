# WeKit Zygisk Module

WeKit can be loaded through Zygisk on a per-Android-user, per-package basis.
The module is disabled for every process immediately after installation.

## KernelSU WebUI

Open the WeKit module page in KernelSU to manage injection targets.

- The first page open scans every Android user and adds every installed package
  matching `PackageNames.isWeChat` (`com.tencent.mm*`) as a disabled target.
- Package discovery uses KernelSU's root-shell `exec` API to run
  `/system/bin/pm list users` and `pm list packages --user <id>`; it does not
  use KernelSU's `listPackages` or `getPackagesInfo` APIs.
- Enabling one instance injects its main process and every process named
  `<package>:...` for that same Android user at the next process launch.
- Refresh scans all Android users again, replaces the package membership with
  the current result, preserves switches for surviving rows, and disables newly
  discovered rows. The WebUI intentionally has no manual add or delete action.

The persisted target list is `/data/adb/wekit_zygisk/injection-targets.tsv`. Module
updates retain it; uninstall removes it without touching app data.

## Installation and updates

Every WeKit APK is also a Zygisk module ZIP. Rename `.apk` to `.zip`, install it
from your root manager, select the target instances in the WebUI, and restart as
required by the manager. APK installation and module installation update their
respective deployments separately. Do not enable both injection modes for the
same WeChat instance.

The installer stores the original signed package as `$MODPATH/module.apk` and
extracts its loader into `zygisk/arm64-v8a.so`. DEX stays inside the APK. The native
loader copies that APK into the host's private directory under a content hash,
then reads its DEX into memory. Resources, native libraries and child processes
use the same APK version.

The installer retains `MODULE_HOT_INSTALL_REQUEST=true` for compatible root
managers. This is not a guarantee that native loader updates can take effect
without rebooting, and it never replaces code in an already running process.
The APK and loader follow the manager's module activation lifecycle together.

## Build

```bash
# Both standard and legacy dual-format APKs (arm64-v8a).
./x build
./x build --release

# Prepare application and Zygisk native libraries without building an APK.
./x build --native-only

# Also export the unstripped Zygisk loader symbols.
./x build --save-symbols

# Normal Android installation.
./x run

# Build and install as a module; omit --root for manager auto-detection.
./x run --zygisk --device SERIAL --root ksu --reboot
./x run --zygisk --flavor legacy --release
```

APKs are in `app/build/outputs/apk/<flavor>/<type>/`. No separate module ZIP is
built or published. The device-side `.zip` used by `run --zygisk` has exactly the
APK's bytes. Symbols are in `target/zygisk-symbols/`.
Run `./x build --help` or `./x run --help` for options.

## Development environment

- Rust toolchain with the Android targets
- rust-analyzer
- Android NDK pinned in `gradle/libs.versions.toml`
- CMake 3.28 or newer and Ninja (CI uses CMake 3.31.6 and Ninja 1.11.1.4)

`./x build` initializes the pinned LSPlant and Dobby sources and LSPlant's
runtime dependencies. LSPlant's unrelated test/documentation submodules are
not required. `./x configure` writes the selected NDK and API level into the
Cargo configuration; direct Android Cargo builds also need this configuration
and the initialized native dependencies. Desktop Rust tests do not build LSPlant.

## ART hooks

The Zygisk lifecycle, companion IPC, payload loading, JNI registration and ART
symbol resolution remain in Rust. A small C ABI bridge statically links
[LSPlant](https://github.com/LSPosed/LSPlant) and
[Dobby](https://github.com/LSPosed/Dobby), including the C++ runtime, into the
existing `libwekit_zygisk.so`. No additional loader-side shared library is needed.
The gitlinks pin LSPlant to `d8b5d1dbb664abc606644036822e4bb64547edf6` and Dobby to
`edb2af1216313cf6c0d6771be2b279c1db573faf` (the source used by LSPlant's Dobby 1.2
test dependency); their upstream license files remain in
the submodules (LSPlant: LGPL-3.0, Dobby: Apache-2.0).

LSPlant initializes during native `postAppSpecialize`, before entering module
Java code, and trusts the module's in-memory DEX files. Kotlin retains the
`IHookBridge` callback contract, including priorities, mutable arguments,
before/after callbacks, original invocation and constructor handling. Explicit
method deoptimization is provided by LSPlant.

Hook targets may be ordinary methods, JNI/native methods, methods declared by
generated `java.lang.reflect.Proxy` classes, concrete interface methods
(default, static or private), and ordinary constructors. Abstract declarations
remain rejected: for an abstract interface method, hook the concrete method on
the implementing/proxy class. Generated proxy constructors also remain rejected
because the pinned LSPlant's proxy signature path calls `Method.getReturnType()`
on the reflected target, which is invalid for a `Constructor`. Native method
hooks intercept ART method calls; they do not intercept direct calls to the
underlying C/C++ function. LSPlant returns false when deoptimizing native methods.

Unhooking a handle removes only its callback. The underlying LSPlant hook and
backup remain alive until process exit, and an empty callback list invokes the
original method directly. This preserves in-flight calls and allows later
registrations to reuse the hook; it retains generated code and some dispatch
overhead. `hookCounter` and `hookedMethods` describe members with active callbacks.
This follows LSPosed's logical-unhook lifecycle: LSPlant does not permit using a
backup after physical unhooking.

On a device, validate startup, static/instance/constructor hooks, native and
generated proxy methods, concrete interface methods, argument and result
replacement, original exceptions, callback ordering, concurrent calls,
logical unhook/re-registration and explicit deoptimization. Desktop tests and a
successful native build cannot establish ART or WeChat runtime compatibility.

## See also

<https://github.com/topjohnwu/zygisk-module-sample>
