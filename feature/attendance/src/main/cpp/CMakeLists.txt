cmake_minimum_required(VERSION 3.22.1)

project("acoustic_dsp")

add_library(
    acoustic_dsp
    SHARED
    acoustic_dsp.cpp
)

find_library(log-lib log)
find_library(m-lib m)

target_link_libraries(
    acoustic_dsp
    ${log-lib}
    ${m-lib}
)

target_compile_options(acoustic_dsp PRIVATE -O3 -ffast-math)
