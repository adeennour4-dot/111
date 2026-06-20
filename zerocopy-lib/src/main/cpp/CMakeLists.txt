cmake_minimum_required(VERSION 3.22.1)
project("zerocopy_engine" CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARM_ARCH_FLAGS "-march=armv8.6-a+dotprod+i8mm+fp16")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   ${ARM_ARCH_FLAGS}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ARM_ARCH_FLAGS}")
endif()

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -flto=thin")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -flto=thin")

option(GGML_VULKAN "Enable Vulkan" OFF)
option(GGML_OPENCL "Enable OpenCL" OFF)
set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE     OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9581
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

find_library(log-lib log)
find_library(android-lib android)

add_library(zerocopy_engine SHARED zerocopy_bridge.cpp)
target_include_directories(zerocopy_engine PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
    ${llama_SOURCE_DIR}/examples
)
target_link_libraries(zerocopy_engine PRIVATE llama ggml ${log-lib} ${android-lib})
target_compile_features(zerocopy_engine PRIVATE cxx_std_20)

if(GGML_VULKAN)
    find_path(VULKAN_HEADERS_INCLUDE_DIR
        NAMES vulkan/vulkan.h
        PATHS ${CMAKE_ANDROID_NDK}/sources/third_party/vulkan/include
    )
    if(VULKAN_HEADERS_INCLUDE_DIR)
        target_include_directories(zerocopy_engine PRIVATE ${VULKAN_HEADERS_INCLUDE_DIR})
        find_library(Vulkan_LIB vulkan PATHS ${CMAKE_ANDROID_NDK}/platforms/android-27/arch-arm64/usr/lib)
        if(Vulkan_LIB)
            target_link_libraries(zerocopy_engine PRIVATE ${Vulkan_LIB})
        endif()
    endif()
endif()