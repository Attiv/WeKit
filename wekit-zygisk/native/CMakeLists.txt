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
set(DOBBY_SOURCE "${WEKIT_ROOT}/third_party/dobby")
if(NOT EXISTS "${LSPLANT_SOURCE}/external/dex_builder/external/parallel_hashmap/CMakeLists.txt"
   OR NOT EXISTS "${DOBBY_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "Missing LSPlant/Dobby sources; prepare them with ./x build --native-only")
endif()

set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LSPLANT_STANDALONE OFF CACHE BOOL "" FORCE)
add_subdirectory("${LSPLANT_SOURCE}" lsplant EXCLUDE_FROM_ALL)

set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
set(DynamicBinaryInstrument OFF CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
set(Plugin.GlobalOffsetTableHook OFF CACHE BOOL "" FORCE)
set(Plugin.Android.BionicLinkerRestriction OFF CACHE BOOL "" FORCE)
add_subdirectory("${DOBBY_SOURCE}" dobby EXCLUDE_FROM_ALL)
# Dobby's intrusive-list macros use GNU typeof.
set_target_properties(dobby PROPERTIES CXX_EXTENSIONS ON)

# This pinned Dobby includes android/log.h inside a C function. Include it at
# file scope first: recent NDK headers also define inline functions there.
target_compile_options(dobby PRIVATE "$<$<COMPILE_LANGUAGE:C>:-include;android/log.h>")

add_library(wekit_lsplant_bridge STATIC cpp/lsplant_bridge.cpp)
target_link_libraries(wekit_lsplant_bridge PRIVATE lsplant_static dobby)
target_compile_options(wekit_lsplant_bridge PRIVATE -Wall -Wextra -Werror -fexceptions)

# Cargo performs the final cdylib link. Export just the required archives;
# upstream's shared libraries, tests and documentation are not built or packaged.
install(FILES
    $<TARGET_FILE:wekit_lsplant_bridge>
    $<TARGET_FILE:lsplant_static>
    $<TARGET_FILE:dex_builder_static>
    $<TARGET_FILE:dobby>
    "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/libc++_static.a"
    "${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/libc++abi.a"
    DESTINATION lib)
