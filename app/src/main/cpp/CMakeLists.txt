cmake_minimum_required(VERSION 3.22.1)
project("kern_pty")

add_library(kern_pty SHARED pty.c)

find_library(log-lib log)
target_link_libraries(kern_pty ${log-lib})
