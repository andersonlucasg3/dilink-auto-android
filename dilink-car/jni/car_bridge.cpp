#include "../pipeline/car_pipeline.h"
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>

#define LOG_TAG "dilink-car.JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace dilink::car;

static CarPipeline g_pipeline;
static JavaVM* g_jvm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    LOGI("libdilink-car loaded");
    return JNI_VERSION_1_6;
}

// ── nativeStart ──

extern "C" JNIEXPORT jint JNICALL
Java_com_dilinkauto_server_NativeCarBridge_nativeStart(
    JNIEnv* env, jclass,
    jint video_port, jint input_port,
    jobject surface_obj,
    jint display_w, jint display_h,
    jint encode_w, jint encode_h) {

    ANativeWindow* surface = nullptr;
    if (surface_obj) {
        surface = ANativeWindow_fromSurface(env, surface_obj);
    }

    int result = g_pipeline.start(video_port, input_port,
                                   surface,
                                   display_w, display_h,
                                   encode_w, encode_h);

    if (result != 0) {
        LOGE("nativeStart failed: %d", result);
        g_pipeline.stop();  // Clean up any bound sockets
    }

    return result;
}

// ── nativeStop ──

extern "C" JNIEXPORT void JNICALL
Java_com_dilinkauto_server_NativeCarBridge_nativeStop(JNIEnv*, jclass) {
    g_pipeline.stop();
}

// ── nativeSetSurface ──

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dilinkauto_server_NativeCarBridge_nativeSetSurface(
    JNIEnv* env, jclass, jobject surface_obj) {

    ANativeWindow* surface = nullptr;
    if (surface_obj) {
        surface = ANativeWindow_fromSurface(env, surface_obj);
    }
    if (!surface) return JNI_FALSE;

    bool ok = g_pipeline.set_surface(surface);
    ANativeWindow_release(surface); // set_surface acquires its own ref
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── Touch injection (called from Compose on UI thread) ──

extern "C" JNIEXPORT void JNICALL
Java_com_dilinkauto_server_NativeCarBridge_nativeTouchDown(
    JNIEnv*, jclass, jint x, jint y, jint pointer_id, jfloat pressure) {
    g_pipeline.send_touch_down(static_cast<int>(x), static_cast<int>(y),
                                static_cast<int>(pointer_id), pressure);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dilinkauto_server_NativeCarBridge_nativeTouchMove(
    JNIEnv*, jclass, jint x, jint y, jint pointer_id, jfloat pressure) {
    g_pipeline.send_touch_move(static_cast<int>(x), static_cast<int>(y),
                                static_cast<int>(pointer_id), pressure);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dilinkauto_server_NativeCarBridge_nativeTouchUp(
    JNIEnv*, jclass, jint x, jint y, jint pointer_id, jfloat pressure) {
    g_pipeline.send_touch_up(static_cast<int>(x), static_cast<int>(y),
                              static_cast<int>(pointer_id), pressure);
}

// ── State queries ──

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dilinkauto_server_NativeCarBridge_nativeIsRunning(JNIEnv*, jclass) {
    return g_pipeline.is_running() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dilinkauto_server_NativeCarBridge_nativeHasReceivedFrame(JNIEnv*, jclass) {
    return g_pipeline.has_received_frame() ? JNI_TRUE : JNI_FALSE;
}
