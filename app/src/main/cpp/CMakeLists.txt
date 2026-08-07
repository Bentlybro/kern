cmake_minimum_required(VERSION 3.22.1)
project("foldcode_pty")

add_library(foldcode_pty SHARED pty.c)

find_library(log-lib log)
target_link_libraries(foldcode_pty ${log-lib})
