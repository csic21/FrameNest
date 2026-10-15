cmake_minimum_required(VERSION 3.22.1)
project(framenest_hymt LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
option(HY_MT_OPTIMIZE "Optimize inference even in a debuggable Android APK" ON)

# Official llama.cpp v0.6.0/b11429; immutable source and content checksum.
# No binary downloads, server, networking, subprocesses or third-party kernels.
set(LLAMA_COMMIT d81235049384534c167caea52b85a694f6103d14)
set(LLAMA_ARCHIVE_SHA256 6b58785f0a82898f4c3442417ff962e2d4b231b1bee5d033902ad90b27901e14)
set(LLAMA_ARCHIVE "${CMAKE_BINARY_DIR}/llama-${LLAMA_COMMIT}.tar.gz")
if(NOT EXISTS "${LLAMA_ARCHIVE}")
    file(DOWNLOAD "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/${LLAMA_COMMIT}"
        "${LLAMA_ARCHIVE}" EXPECTED_HASH "SHA256=${LLAMA_ARCHIVE_SHA256}"
        TLS_VERIFY ON STATUS LLAMA_DOWNLOAD_STATUS)
    list(GET LLAMA_DOWNLOAD_STATUS 0 LLAMA_DOWNLOAD_CODE)
    if(NOT LLAMA_DOWNLOAD_CODE EQUAL 0)
        message(FATAL_ERROR "Pinned llama.cpp source download failed: ${LLAMA_DOWNLOAD_STATUS}")
    endif()
endif()
file(SHA256 "${LLAMA_ARCHIVE}" LLAMA_ACTUAL_SHA256)
if(NOT LLAMA_ACTUAL_SHA256 STREQUAL LLAMA_ARCHIVE_SHA256)
    message(FATAL_ERROR "Pinned llama.cpp source checksum mismatch")
endif()
file(ARCHIVE_EXTRACT INPUT "${LLAMA_ARCHIVE}" DESTINATION "${CMAKE_BINARY_DIR}/pinned-source")
set(LLAMA_SOURCE "${CMAKE_BINARY_DIR}/pinned-source/llama.cpp-${LLAMA_COMMIT}")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
foreach(option LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES
        LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_BUILD_UI LLAMA_OPENSSL LLAMA_SUBPROCESS
        GGML_NATIVE GGML_OPENMP GGML_CPU_KLEIDIAI GGML_BACKEND_DL GGML_RPC
        GGML_CUDA GGML_VULKAN GGML_METAL GGML_BLAS GGML_LLAMAFILE GGML_ACCELERATE
        GGML_HIP GGML_MUSA GGML_WEBGPU GGML_ZDNN GGML_ZENDNN GGML_SYCL
        GGML_OPENVINO GGML_OPENCL GGML_HEXAGON GGML_ET GGML_ET_SYSEMU
        GGML_VIRTGPU GGML_VIRTGPU_BACKEND GGML_CPU_ALL_VARIANTS GGML_OPENMP_FETCH)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
# Portable Android ABI baseline, independent of build-host ISA. SSE4.2 is part
# of Android x86_64; AVX/AMX and newer ARM extensions are not universal.
set(GGML_SSE42 ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
foreach(option GGML_AVX GGML_AVX2 GGML_AVX_VNNI GGML_AVX512 GGML_AVX512_VBMI
        GGML_AVX512_VNNI GGML_AVX512_BF16 GGML_FMA GGML_F16C GGML_BMI2
        GGML_AMX_TILE GGML_AMX_INT8 GGML_AMX_BF16)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory("${LLAMA_SOURCE}" llama-build)
add_library(framenest_hymt SHARED hy_mt_jni.cpp)
target_link_libraries(framenest_hymt PRIVATE llama)
target_compile_options(framenest_hymt PRIVATE -Wall -Wextra -Werror)
target_link_options(framenest_hymt PRIVATE "-Wl,--exclude-libs,ALL")
# AGP's debug variant passes CMAKE_BUILD_TYPE=Debug. Upstream only defaults to
# Release when the build type is empty, leaving Debug kernels unoptimized.
# Optimize the actual inference targets, not just this JNI wrapper. Do not
# replace global flags or set NDEBUG: keep symbols, assertions, sanitizer flags,
# cancellation and Java/Kotlin debuggability. OFF is for native source debugging
# and O0/O3 comparisons, never for performance acceptance.
if(HY_MT_OPTIMIZE)
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES "^(Clang|AppleClang|GNU)$")
        message(FATAL_ERROR "HY_MT_OPTIMIZE requires the pinned Clang/GNU toolchain")
    endif()
    foreach(target ggml-base ggml-cpu ggml llama framenest_hymt)
        if(NOT TARGET ${target})
            message(FATAL_ERROR "Missing inference target: ${target}")
        endif()
        target_compile_options(${target} PRIVATE -O3)
    endforeach()
endif()
message(STATUS "Hy-MT inference optimization: ${HY_MT_OPTIMIZE}; build type: ${CMAKE_BUILD_TYPE}")
# Android 15+ devices may use 16 KiB pages. NDK r28 also defaults to this.
if(ANDROID)
    target_link_options(framenest_hymt PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Host JNI smoke tests use the same bridge; these do not establish Android performance.
    find_path(HY_MT_JNI_INCLUDE jni.h HINTS "$ENV{JAVA_HOME}/include" REQUIRED)
    find_path(HY_MT_JNI_PLATFORM_INCLUDE jni_md.h HINTS "$ENV{JAVA_HOME}/include/linux" REQUIRED)
    target_include_directories(framenest_hymt PRIVATE ${HY_MT_JNI_INCLUDE} ${HY_MT_JNI_PLATFORM_INCLUDE})
endif()
