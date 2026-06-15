cmake_minimum_required(VERSION 3.22.1)
project("zerocopy_engine_v8" CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARM_ARCH_FLAGS "-march=armv8.6-a+dotprod+i8mm+fp16")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   ${ARM_ARCH_FLAGS}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ARM_ARCH_FLAGS}")
endif()

option(GGML_VULKAN "Enable Vulkan" OFF)
option(GGML_OPENCL "Enable OpenCL" OFF)
set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE     OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_FLASH_ATTN ON CACHE BOOL "" FORCE)

# Vulkan headers from NDK
if(GGML_VULKAN)
    find_path(VULKAN_HEADERS_INCLUDE_DIR
        NAMES vulkan/vulkan.h
        PATHS ${CMAKE_ANDROID_NDK}/sources/third_party/vulkan/include
    )
    if(VULKAN_HEADERS_INCLUDE_DIR)
        message(STATUS "Vulkan headers found: ${VULKAN_HEADERS_INCLUDE_DIR}")
        set(GGML_VULKAN_HEADERS_DIR ${VULKAN_HEADERS_INCLUDE_DIR} CACHE PATH "" FORCE)
    else()
        message(WARNING "Vulkan headers not found in NDK, disabling Vulkan")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9581
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

set(MNN_BUILD_SHARED_LIBS ON  CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM         ON  CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO        OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST        OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TRAIN       OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_QUANTOOLS   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DIFFUSION   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV      OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_AUDIO       OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY        ON  CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_ARM82             OFF CACHE BOOL "" FORCE)
set(MNN_KLEIDIAI          OFF CACHE BOOL "" FORCE)
set(MNN_SME2              OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL            OFF CACHE BOOL "" FORCE)
set(MNN_VULKAN            OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD         OFF CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT        ON  CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_USE_SSE           OFF CACHE BOOL "" FORCE)
set(MNN_USE_THREAD_POOL   ON  CACHE BOOL "" FORCE)
set(MNN_FORBID_MULTI_THREAD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_PROTOBUFFER OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    mnn
    GIT_REPOSITORY https://github.com/alibaba/MNN.git
    GIT_TAG        3.5.0
    GIT_SHALLOW    TRUE
    GIT_SUBMODULES ""
    GIT_SUBMODULES_RECURSE FALSE
)
FetchContent_GetProperties(mnn)
if(NOT mnn_POPULATED)
    FetchContent_Populate(mnn)
endif()
set(CMAKE_SOURCE_DIR "${mnn_SOURCE_DIR}")
add_subdirectory("${mnn_SOURCE_DIR}" "${mnn_BINARY_DIR}")
set(CMAKE_SOURCE_DIR "${PROJECT_SOURCE_DIR}")

find_library(log-lib     log)
find_library(android-lib android)

add_library(ipc-bridge SHARED ipc-bridge.cpp)
target_include_directories(ipc-bridge PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)
target_link_libraries(ipc-bridge PRIVATE llama ggml ${log-lib} ${android-lib})

add_library(mnn-bridge SHARED mnn-bridge.cpp)
target_include_directories(mnn-bridge PRIVATE
    ${mnn_SOURCE_DIR}/include/
    ${mnn_SOURCE_DIR}/source/
    ${mnn_SOURCE_DIR}/express/
    ${mnn_SOURCE_DIR}/transformers/llm/engine/include/
    ${mnn_SOURCE_DIR}/3rd_party/
    ${mnn_SOURCE_DIR}/3rd_party/half/
    ${mnn_SOURCE_DIR}/3rd_party/flatbuffers/include/
)
target_link_libraries(mnn-bridge PRIVATE MNN ${log-lib} ${android-lib})

if(GGML_VULKAN)
    find_library(Vulkan_LIB vulkan PATHS ${CMAKE_ANDROID_NDK}/platforms/android-27/arch-arm64/usr/lib)
    if(Vulkan_LIB)
        target_include_directories(ipc-bridge PRIVATE ${VULKAN_HEADERS_INCLUDE_DIR})
        target_link_libraries(ipc-bridge PRIVATE ${Vulkan_LIB})
        message(STATUS "Vulkan enabled for ipc-bridge")
    endif()
endif()

target_compile_features(ipc-bridge PRIVATE cxx_std_20)
target_compile_features(mnn-bridge PRIVATE cxx_std_20)
