cmake_minimum_required(VERSION 3.22.1)
# The exclusive AAudio backend. The name used to be `sertumspike`, after the spike that proved the
# approach; the spike's sources are gone and only the shipped backend remains.
project(sertumaaudio CXX)

add_library(sertumaaudio SHARED aaudio_backend.cpp)

find_library(aaudio-lib aaudio)
find_library(android-lib android)
find_library(log-lib log)

target_link_libraries(sertumaaudio ${aaudio-lib} ${android-lib} ${log-lib})
