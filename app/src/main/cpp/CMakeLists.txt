cmake_minimum_required(VERSION 3.10.2)
project("fontredirect")

add_library(
        fontredirect
        SHARED
        fontredirect.c)

find_library(log-lib log)

target_link_libraries(fontredirect ${log-lib})
