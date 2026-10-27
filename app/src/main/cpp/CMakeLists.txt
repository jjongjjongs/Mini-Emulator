cmake_minimum_required(VERSION 3.22.1)
project(gbabridge CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(gbabridge SHARED gbabridge.cpp vulkan_hw.cpp dsnet.cpp)

target_compile_options(gbabridge PRIVATE -Wall -Wextra)
# 16 KB page alignment, required for devices with 16 KB memory pages.
target_link_options(gbabridge PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(gbabridge android log dl)
