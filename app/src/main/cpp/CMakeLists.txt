cmake_minimum_required(VERSION 3.22.1)
# The exclusive AAudio backend. The name used to be `sertumspike`, after the spike that proved the
# approach; the spike's sources are gone and only the shipped backend remains.
project(sertumaaudio CXX)

add_library(sertumaaudio SHARED aaudio_backend.cpp)

find_library(aaudio-lib aaudio)
find_library(android-lib android)
find_library(log-lib log)

target_link_libraries(sertumaaudio ${aaudio-lib} ${android-lib} ${log-lib})

# The UAC2 isochronous transport (ADR-0001). No third-party library: Android has no public
# isochronous API, so this drives usbdevfs directly through the descriptor's own file descriptor.
add_library(sertumuac2 SHARED uac2_transport.cpp)
target_link_libraries(sertumuac2 ${log-lib})