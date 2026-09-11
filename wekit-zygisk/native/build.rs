use std::env;
use std::path::PathBuf;
use std::process::Command;

fn run(command: &mut Command) {
    let status = command.status().unwrap_or_else(|error| {
        panic!("failed to run {command:?}: {error}; install CMake >= 3.28 and Ninja")
    });
    assert!(status.success(), "{command:?} exited with {status}");
}

fn main() {
    println!("cargo:rerun-if-env-changed=WEKIT_ANDROID_NDK");
    println!("cargo:rerun-if-env-changed=WEKIT_ANDROID_API");
    println!("cargo:rerun-if-env-changed=CMAKE");
    // APK/protocol unit tests run on the desktop without Android or a JVM.
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }
    assert_eq!(
        env::var("TARGET").unwrap(),
        "aarch64-linux-android",
        "the Zygisk loader currently supports arm64-v8a only"
    );

    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let root = manifest.parent().unwrap().parent().unwrap();
    let ndk = PathBuf::from(
        env::var_os("WEKIT_ANDROID_NDK")
            .expect("run ./x configure to select the pinned Android NDK"),
    );
    let api = env::var("WEKIT_ANDROID_API").expect("run ./x configure to select the Android API");
    for source in [
        manifest.join("CMakeLists.txt"),
        manifest.join("cpp"),
        root.join("third_party/lsplant/lsplant/src/main/jni"),
    ] {
        println!("cargo:rerun-if-changed={}", source.display());
    }

    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let build = out.join("cmake");
    let install = out.join("native");
    let cmake = env::var_os("CMAKE").unwrap_or_else(|| "cmake".into());
    run(Command::new(&cmake)
        .arg("-S")
        .arg(&manifest)
        .arg("-B")
        .arg(&build)
        .args(["-G", "Ninja"])
        .arg(format!(
            "-DCMAKE_TOOLCHAIN_FILE={}",
            ndk.join("build/cmake/android.toolchain.cmake").display()
        ))
        .arg("-DANDROID_ABI=arm64-v8a")
        .arg(format!("-DANDROID_PLATFORM=android-{api}"))
        .arg("-DANDROID_STL=c++_static")
        .arg("-DCMAKE_BUILD_TYPE=RelWithDebInfo")
        .arg(format!("-DCMAKE_INSTALL_PREFIX={}", install.display())));
    run(Command::new(&cmake)
        .arg("--build")
        .arg(&build)
        .args(["--target", "wekit_lsplant_bridge", "--parallel"])
        .arg(env::var("NUM_JOBS").unwrap_or_else(|_| "1".into())));
    run(Command::new(&cmake).arg("--install").arg(&build));

    println!(
        "cargo:rustc-link-search=native={}",
        install.join("lib").display()
    );
    for library in [
        "wekit_lsplant_bridge",
        "lsplant_static",
        "dex_builder_static",
        "wekit_compiler_rt",
    ] {
        println!("cargo:rustc-link-lib=static={library}");
    }
    // The loader is extracted on its own by the module installer. Do not add
    // libc++_shared.so (or another application-private shared library) dependencies.
    // CMake copies only these two archives into our native search directory.
    // Adding the NDK's generic library directory to -L would shadow the Clang
    // driver's API-specific libc.so with libc.a (and duplicate Rust symbols).
    // The Android Clang driver adds the matching libunwind.a automatically.
    for library in ["c++_static", "c++abi"] {
        println!("cargo:rustc-link-lib=static={library}");
    }
    for library in ["log", "z", "dl", "m"] {
        println!("cargo:rustc-link-lib={library}");
    }
    println!("cargo:rustc-link-arg=-Wl,--exclude-libs,ALL");
    println!("cargo:rustc-link-arg=-Wl,--no-undefined");
}
