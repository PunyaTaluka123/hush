cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(hush)

target_include_directories(app PRIVATE lib src)
target_sources(app PRIVATE src/main.c src/ble.c src/sensors.c lib/hush_capi.cpp)
