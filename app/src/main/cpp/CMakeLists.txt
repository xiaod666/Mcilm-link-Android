cmake_minimum_required(VERSION 3.18)
project(mcilm_jni LANGUAGES CXX)

# 显式 C++17（NDK 默认是 C++14，部分新语法/库特性需要 17+）。
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(mcilm_jni SHARED native-lib.cpp)

# dl 用于 dlopen / dlsym / dlclose，log 用于 __android_log_print。
# （必须链接 dl，否则 Android 5.0+ 上会因 linker 隔离找不到 dlopen 符号。）
target_link_libraries(mcilm_jni log dl android)

# Android 14+ 16KB page size 兼容（Pixel 8 及未来设备）。
target_link_options(mcilm_jni PRIVATE "-Wl,-z,max-page-size=16384")