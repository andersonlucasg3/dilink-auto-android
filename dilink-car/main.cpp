// dilink-car — Native car-side video decoder + touch input.
// Loaded by the car APK via System.loadLibrary("dilink-car").
// JNI entry points are in jni/car_bridge.cpp.
// This file exists for CMake registration of the shared library target.
#include "pipeline/car_pipeline.h"
#include "jni/../network/car_tcp.h"
#include "jni/../network/car_input.h"
#include "jni/../decoder/video_decoder.h"
