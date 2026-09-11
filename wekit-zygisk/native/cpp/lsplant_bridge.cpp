#include <android/log.h>
#include <dobby.h>
#include <jni.h>
#include <lsplant.hpp>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <string_view>
#include <sys/mman.h>
#include <unistd.h>

using ArtSymbolResolver = void *(*)(const char *, std::size_t, bool);

namespace {

// The pinned Dobby's POSIX CodePatch only protects the first page. Its arm64
// target trampoline is at most 16 bytes (LDR + BR + address); cover both pages
// when that prologue straddles a boundary, for installation and restoration.
// Use the device's page size, including on 16 KiB Android devices.
class WritableArtCode {
public:
    explicit WritableArtCode(void *target) {
        const long page_size = sysconf(_SC_PAGESIZE);
        if (!target || page_size <= 0) return;
        const auto address = reinterpret_cast<std::uintptr_t>(target);
        const auto page = static_cast<std::uintptr_t>(page_size);
        const auto start = address / page * page;
        const auto end = (address + 15) / page * page + page;
        start_ = reinterpret_cast<void *>(start);
        size_ = end - start;
        writable_ = mprotect(start_, size_, PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
        if (!writable_) {
            __android_log_print(ANDROID_LOG_ERROR, "WeKit", "Cannot make ART code writable: %p", target);
        }
    }

    ~WritableArtCode() {
        if (writable_ && mprotect(start_, size_, PROT_READ | PROT_EXEC) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "WeKit", "Cannot restore ART code permissions: %p", start_);
        }
    }

    explicit operator bool() const { return writable_; }

private:
    void *start_ = nullptr;
    std::size_t size_ = 0;
    bool writable_ = false;
};

void report_exception(JNIEnv *env, const char *operation, const char *message) noexcept {
    __android_log_print(ANDROID_LOG_ERROR, "WeKit", "LSPlant %s: %s", operation, message);
    if (!env->ExceptionCheck()) {
        if (jclass exception = env->FindClass("java/lang/IllegalStateException")) {
            env->ThrowNew(exception, message);
            env->DeleteLocalRef(exception);
        }
    }
}

template <typename Result, typename Function>
Result guarded(JNIEnv *env, const char *operation, Result failure, Function function) noexcept {
    try {
        return function();
    } catch (const std::exception &error) {
        report_exception(env, operation, error.what());
    } catch (...) {
        report_exception(env, operation, "unknown C++ exception");
    }
    return failure;
}

} // namespace

// Rust owns initialization serialization and the ART symbol resolver's lifetime.
extern "C" bool wekit_lsplant_init(JNIEnv *env, ArtSymbolResolver resolver) noexcept {
    return guarded(env, "Init", false, [&] {
        lsplant::InitInfo info{
            .inline_hooker = [](void *target, void *replacement) -> void * {
                WritableArtCode code(target);
                if (!code) return nullptr;
                void *backup = nullptr;
                return DobbyHook(target, replacement, &backup) == 0 ? backup : nullptr;
            },
            .inline_unhooker = [](void *target) {
                WritableArtCode code(target);
                return code && DobbyDestroy(target) == 0;
            },
            .art_symbol_resolver = [resolver](std::string_view name) {
                return resolver(name.data(), name.size(), false);
            },
            .art_symbol_prefix_resolver = [resolver](std::string_view name) {
                return resolver(name.data(), name.size(), true);
            },
            .executable_memory_allocator = {},
            .executable_memory_recycler = {},
        };
        return lsplant::Init(env, info);
    });
}

extern "C" jobject wekit_lsplant_hook(JNIEnv *env, jobject target, jobject hooker,
                                      jobject callback) noexcept {
    return guarded(env, "Hook", static_cast<jobject>(nullptr), [&]() -> jobject {
        jobject backup = lsplant::Hook(env, target, hooker, callback);
        // LSPlant retains and owns its global reference. JNI callers receive a
        // separate local reference and must never delete the upstream global.
        return backup ? env->NewLocalRef(backup) : nullptr;
    });
}

extern "C" bool wekit_lsplant_is_hooked(JNIEnv *env, jobject target) noexcept {
    return guarded(env, "IsHooked", false, [&] { return lsplant::IsHooked(env, target); });
}

extern "C" bool wekit_lsplant_deoptimize(JNIEnv *env, jobject target) noexcept {
    return guarded(env, "Deoptimize", false, [&] { return lsplant::Deoptimize(env, target); });
}

extern "C" bool wekit_lsplant_make_dex_file_trusted(JNIEnv *env, jobject cookie) noexcept {
    return guarded(env, "MakeDexFileTrusted", false,
                   [&] { return lsplant::MakeDexFileTrusted(env, cookie); });
}
