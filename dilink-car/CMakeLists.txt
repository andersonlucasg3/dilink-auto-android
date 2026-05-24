cmake_minimum_required(VERSION 3.22.1)
project("dilink-car" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(dilink-car SHARED
    main.cpp
    decoder/video_decoder.cpp
    network/car_tcp.cpp
    network/car_input.cpp
    pipeline/car_pipeline.cpp
    pipeline/frame_queue.cpp
    jni/car_bridge.cpp
    ../native-shared/protocol.cpp
)

target_include_directories(dilink-car PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../native-shared
)

find_library(log-lib log)
find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(dilink-car
    ${mediandk-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv2-lib}
    ${log-lib}
)

target_compile_options(dilink-car PRIVATE
    -Wall -Wextra -Werror
    -fno-exceptions -fno-rtti
    -O2
)
