cmake_minimum_required(VERSION 3.22.1)
project(usbaudio C)
add_library(usbaudio SHARED usbaudio.c)
target_link_libraries(usbaudio log)
