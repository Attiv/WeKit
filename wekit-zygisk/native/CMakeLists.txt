cmake_minimum_required(VERSION 3.28)
project(wekit_lsplant LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The WeKit Zygisk bridge requires Android arm64-v8a")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

get_filename_component(WEKIT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(LSPLANT_SOURCE "${WEKIT_ROOT}/third_party/lsplant/lsplant/src/main/jni")
if(NOT EXISTS "${LSPLANT_SOURCE}/external/dex_builder/external/parallel_hashmap/CMakeLists.txt")
    message(FATAL_ERROR "Missing LSPlant sources; prepare them with ./x build --native-only")
endif()

set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LSPLANT_STANDALONE OFF CACHE BOOL "" FORCE)
add_subdirectory("${LSPLANT_SOURCE}" lsplant EXCLUDE_FROM_ALL)

add_library(wekit_lsplant_bridge STATIC cpp/lsplant_bridge.cpp)
target_link_libraries(wekit_lsplant_bridge PRIVATE lsplant_static)
target_compile_options(wekit_lsplant_bridge PRIVATE -Wall -Wextra -Werror -fexceptions)

# Cargo supplies Rust's builtins but LSPlant also emits Clang's __clear_cache.
# Link the NDK's matching compiler-rt archive; Dobby previously supplied that
# symbol incidentally. Copy the archive rather than adding a generic NDK -L path.
execute_process(
    COMMAND "${CMAKE_CXX_COMPILER}" --target=aarch64-linux-android --print-libgcc-file-name
    OUTPUT_VARIABLE WEKIT_COMPILER_RT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    COMMAND_ERROR_IS_FATAL ANY)
if(NOT EXISTS "${WEKIT_COMPILER_RT}")
    message(FATAL_ERROR "Android compiler runtime not found: ${WEKIT_COMPILER_RT}")
endif()

# Cargo performs the final cdylib link. Export just the required archives;
# upstream's shared libraries, tests and documentation are not built or packaged.
install(FILES
    $<TARGET_FILE:wekit_lsplant_bridge>
    $<TARGET_FILE:lsplant_static>
    $<TARGET_FILE:dex_builder_static>
    "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/libc++_static.a"
    "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/libc++abi.a"
    DESTINATION lib)
install(FILES "${WEKIT_COMPILER_RT}" DESTINATION lib RENAME libwekit_compiler_rt.a)
