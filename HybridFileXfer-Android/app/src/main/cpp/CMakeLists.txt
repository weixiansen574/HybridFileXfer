cmake_minimum_required(VERSION 3.4.1)

project("hybridfilexfer")

add_library(native-memory SHARED native-memory.cpp)

find_library(log-lib log)

target_link_libraries(native-memory
        android
        log)