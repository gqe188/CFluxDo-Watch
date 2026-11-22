cmake_minimum_required(VERSION 3.22.1)
project("doh_jni" C)

add_library(doh_jni SHARED doh_jni.c)

find_library(log-lib log)

target_link_libraries(doh_jni ${log-lib} dl)
