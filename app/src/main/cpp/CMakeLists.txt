cmake_minimum_required(VERSION 3.22.1)
project("zerocopy_engine_v8" CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# armv8.2-a+dotprod+fp16 covers both older devices (Note 10 Lite) and newer
# ones (S25 Ultra). i8mm is excluded from the global flags because the Note 10
# Lite's Cortex-A76 does not support it, but we add i8mm specifically to the
# ggml target below. ggml's runtime dispatch will only execute i8mm kernels
# on devices that support them (S25 Ultra), so the library is safe on both.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARM_ARCH_FLAGS "-march=armv8.2-a+dotprod+fp16")
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
# Flash attention is enabled via context params, not cmake

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

# ggml's own cmake checks (CheckCXXSourceCompiles) will detect dotprod and
# i8mm support in the compiler and add per-file COMPILE_OPTIONS to the
# specific kernel files that need them. The global march stays at
# armv8.2-a+dotprod+fp16, which is safe for both devices. On S25 Ultra,
# ggml's runtime dispatch will select i8mm-accelerated kernels that were
# compiled per-file with i8mm; on Note 10 Lite those kernels exist in the
# binary but are never called, so no SIGILL occurs.

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

# Multimodal vision support (from llama.cpp tools/mtmd)
# In b9581+, mtmd is the canonical multimodal library built by llama.cpp.
# It includes CLIP + all vision model implementations (llava, qwen2vl, siglip, etc.)
# We use it directly instead of building our own clip target.
set(HAS_VISION_SUPPORT FALSE)

if(TARGET mtmd)
    set(HAS_VISION_SUPPORT TRUE)
    message(STATUS "mtmd library found (b9581+), vision support enabled")
else()
    # Fallback: check for standalone clip in older locations
    set(CLIP_DIRS
        ${llama_SOURCE_DIR}/tools/mtmd
        ${llama_SOURCE_DIR}/examples/llava
        ${llama_SOURCE_DIR}/multimodal
    )
    foreach(CLIP_DIR ${CLIP_DIRS})
        if(EXISTS ${CLIP_DIR}/clip.cpp AND EXISTS ${CLIP_DIR}/clip.h)
            set(STB_FOUND FALSE)
            if(EXISTS ${llama_SOURCE_DIR}/vendor/stb/stb_image.h)
                set(STB_FOUND TRUE)
                set(STB_DIR ${llama_SOURCE_DIR}/vendor/stb)
            elseif(EXISTS ${CLIP_DIR}/stb/stb_image.h)
                set(STB_FOUND TRUE)
                set(STB_DIR ${CLIP_DIR}/stb)
            endif()
            if(STB_FOUND)
                add_library(stb INTERFACE)
                target_include_directories(stb INTERFACE ${STB_DIR})
                file(GLOB CLIP_SOURCES ${CLIP_DIR}/*.cpp ${CLIP_DIR}/models/*.cpp)
                list(FILTER CLIP_SOURCES EXCLUDE REGEX "mtmd-cli|mtmd-debug|deprecation")
                add_library(clip STATIC ${CLIP_SOURCES})
                target_include_directories(clip PRIVATE
                    ${llama_SOURCE_DIR}/include
                    ${llama_SOURCE_DIR}/ggml/include
                    ${CLIP_DIR}
                    ${llama_SOURCE_DIR}/vendor
                )
                target_link_libraries(clip PUBLIC llama ggml stb)
                set_property(TARGET clip PROPERTY POSITION_INDEPENDENT_CODE ON)
                set(HAS_VISION_SUPPORT TRUE)
                message(STATUS "CLIP/vision sources found at ${CLIP_DIR}, building with vision support")
                break()
            else()
                message(WARNING "stb_image.h not found for ${CLIP_DIR}")
            endif()
        endif()
    endforeach()
endif()

if(NOT HAS_VISION_SUPPORT)
    message(WARNING "Vision support (mtmd/clip) not found - vision features disabled")
endif()

add_library(ipc-bridge SHARED ipc-bridge.cpp)
target_include_directories(ipc-bridge PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)
if(HAS_VISION_SUPPORT)
    if(TARGET mtmd)
        # mtmd includes all model implementations - link against it directly
        target_include_directories(ipc-bridge PRIVATE
            ${llama_SOURCE_DIR}/tools/mtmd
            ${llama_SOURCE_DIR}/vendor/stb
        )
        target_link_libraries(ipc-bridge PRIVATE llama ggml mtmd ${log-lib} ${android-lib})
    else()
        # Fallback: standalone clip target with all model sources
        target_include_directories(ipc-bridge PRIVATE
            ${llama_SOURCE_DIR}/tools/mtmd
            ${llama_SOURCE_DIR}/examples/llava
            ${llama_SOURCE_DIR}/vendor/stb
        )
        target_link_libraries(ipc-bridge PRIVATE llama ggml clip ${log-lib} ${android-lib})
    endif()
else()
    target_link_libraries(ipc-bridge PRIVATE llama ggml ${log-lib} ${android-lib})
endif()

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
