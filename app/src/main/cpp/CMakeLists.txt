cmake_minimum_required(VERSION 3.22.1)
project(gbabridge CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(gbabridge SHARED gbabridge.cpp vulkan_hw.cpp dsnet.cpp gclan.cpp)

target_compile_options(gbabridge PRIVATE -Wall -Wextra)
# 16 KB page alignment, required for devices with 16 KB memory pages.
target_link_options(gbabridge PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(gbabridge android log dl)

# "GDI로 풀기" (Dreamcast CHD -> GDI): libchdr, the CHD reader Flycast uses, pinned to a commit.
include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CHDR_WANT_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(libchdr
    GIT_REPOSITORY https://github.com/rtissera/libchdr.git
    GIT_TAG 47d794151b2b64f6aba60304e6480f4f5a2bc24b)
FetchContent_GetProperties(libchdr)
if(NOT libchdr_POPULATED)
    FetchContent_Populate(libchdr)
    add_subdirectory(${libchdr_SOURCE_DIR} ${libchdr_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

add_library(chdgdi SHARED chdgdi.cpp)
target_compile_options(chdgdi PRIVATE -Wall -Wextra)
target_link_options(chdgdi PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(chdgdi chdr-static log)
