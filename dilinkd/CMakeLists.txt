cmake_minimum_required(VERSION 3.22.1)
project("dilinkd" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Build libdilinkd.so — loaded by app_process via System.loadLibrary
add_library(dilinkd SHARED
    main.cpp
    pipeline/pipeline.cpp
    pipeline/frame_pool.cpp
    encoder/amc_encoder.cpp
    encoder/bitrate_ctrl.cpp
    renderer/egl_core.cpp
    renderer/texture_blit.cpp
    renderer/frame_diff.cpp
    network/tcp_stream.cpp
    network/pacing.cpp
    input/touch_parser.cpp
    lifecycle/unix_socket.cpp
    lifecycle/watchdog.cpp
    jni/bridge.cpp
    jni/vd_create.cpp
    jni/input_inject.cpp
    jni/display_power.cpp
    jni/shell_exec.cpp
    ../native-shared/protocol.cpp
)

target_include_directories(dilinkd PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../native-shared
)

find_library(log-lib log)
find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(dilinkd
    ${mediandk-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv2-lib}
    ${log-lib}
)

target_compile_options(dilinkd PRIVATE
    -Wall -Wextra -Werror
    -fno-exceptions -fno-rtti
    -O2 -flto=thin
)
