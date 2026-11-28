cmake_minimum_required(VERSION 3.22.1)
project(gba_bridge C)

add_library(gba_bridge SHARED gba_bridge.c)
target_compile_options(gba_bridge PRIVATE -std=c99 -O2 -Wall)
target_include_directories(gba_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
find_library(log-lib log)
target_link_libraries(gba_bridge ${log-lib} dl)
