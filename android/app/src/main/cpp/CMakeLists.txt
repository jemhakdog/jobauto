cmake_minimum_required(VERSION 3.22)

project("llama-bridge")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Configure llama.cpp build options
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama.cpp build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama.cpp build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama.cpp build server" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama.cpp build common library" FORCE)

include(FetchContent)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b3800
    GIT_SHALLOW    TRUE
)

FetchContent_MakeAvailable(llama)

# Shared library for JNI bridge
add_library(llama-bridge SHARED
    llama-bridge.cpp
)

target_include_directories(llama-bridge PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)

target_link_libraries(llama-bridge PRIVATE
    llama
    log
)
