// dilinkd — Native VirtualDisplay streaming daemon
//
// Entry point: JNI_OnLoad registers native methods.
// Java calls nativeRun() which runs the full pipeline on a native thread.
//
// Launch: CLASSPATH=bridge.jar app_process / com.dilinkauto.vdserver.DaemonEntry <args>
// The Java DaemonEntry loads libdilinkd.so and calls nativeRun().

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cstring>
#include <ctime>
#include <thread>

#include "pipeline/pipeline.h"
#include "network/tcp_stream.h"
#include "network/protocol.h"
#include "input/touch_parser.h"
#include "lifecycle/unix_socket.h"
#include "lifecycle/watchdog.h"
#include "jni/bridge.h"

#define LOG_TAG "dilinkd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace dilink;

// Global state
static Pipeline g_pipeline;
static TcpStream g_video_server;
static TcpStream g_input_server;

// Daemon configuration (parsed from args)
struct DaemonConfig {
    int display_w = 1408;
    int display_h = 792;
    int display_dpi = 120;
    int encode_w = 1408;
    int encode_h = 792;
    int fps = 30;
    char phone_host[256] = "127.0.0.1";
    char lifecycle_path[256] = "/data/local/tmp/dilinkd.sock";
};
static DaemonConfig g_config;

// Touch injection callback (called from touch reader thread → JNI up-call)
static void touch_inject_callback(int action, int x, int y,
                                   int pointer_id, float pressure,
                                   void* user_data) {
    (void)pointer_id; (void)pressure;
    JNIEnv* env = nullptr;
    bool attached = false;

    if (jni::g_jvm) {
        jint ret = jni::g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            jni::g_jvm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
    }

    if (!env) return;

    // TODO: implement proper MotionEvent injection via JNI bridge
    // For now, use shell-based input tap as fallback
    char cmd[256];
    if (action == 0 || action == 1) {
        snprintf(cmd, sizeof(cmd), "input -d %d tap %d %d",
                 static_cast<int>(reinterpret_cast<intptr_t>(user_data)), x, y);
        jni::exec_shell(env, cmd);
    }

    if (attached) {
        jni::g_jvm->DetachCurrentThread();
    }
}

// Watchdog timeout handler (wired in Phase 4)
__attribute__((unused)) static void on_watchdog_timeout(void*) {
    LOGE("Watchdog timeout — restarting daemon");
    g_pipeline.stop();
}

// Touch reader thread: reads touch events from car input connection
static void* touch_reader_thread(void* arg) {
    auto* handler = static_cast<TouchHandler*>(arg);
    TcpStream& stream = g_input_server;

    uint8_t recv_buf[65536];
    size_t recv_off = 0;

    while (g_pipeline.is_running()) {
        ssize_t n = stream.read_some(recv_buf + recv_off, sizeof(recv_buf) - recv_off);
        if (n < 0) {
            break; // error
        }
        if (n == 0) {
            // No data, short sleep
            struct timespec ts = {0, 5'000'000}; // 5ms
            nanosleep(&ts, nullptr);
            continue;
        }

        recv_off += static_cast<size_t>(n);

        // Decode frames from buffer
        size_t consumed = 0;
        while (consumed < recv_off) {
            protocol::Frame frame;
            size_t frame_len = protocol::decode_frame(
                recv_buf + consumed, recv_off - consumed, frame);
            if (frame_len == 0) break; // incomplete frame

            if (frame.channel == protocol::CHANNEL_INPUT) {
                handler->handle_frame(frame);
            } else if (frame.channel == protocol::CHANNEL_CONTROL) {
                // Handle control commands (launch app, go back, etc.)
                // These are forwarded to the phone app via lifecycle channel
            }

            consumed += frame_len;
        }

        // Shift remaining data
        if (consumed > 0 && consumed < recv_off) {
            std::memmove(recv_buf, recv_buf + consumed, recv_off - consumed);
        }
        recv_off -= consumed;
    }

    LOGI("Touch reader exited");
    return nullptr;
}

// Lifecycle reader thread: reads stop commands from phone app
static void* lifecycle_reader_thread(void* arg) {
    auto* lc = static_cast<LifecycleChannel*>(arg);

    while (g_pipeline.is_running()) {
        int cmd = lc->read_command(1000); // 1s timeout
        if (cmd == protocol::CMD_STOP) {
            LOGI("Received CMD_STOP");
            g_pipeline.stop();
            break;
        }
        if (cmd < 0 && !lc->is_connected()) {
            break; // channel closed
        }
    }

    LOGI("Lifecycle reader exited");
    return nullptr;
}

// ── JNI native methods ──

extern "C" JNIEXPORT jint JNICALL
Java_com_dilinkauto_vdserver_DaemonEntry_nativeRun(
    JNIEnv* env, jobject /* thiz */, jobjectArray args_obj,
    jobject bridge_obj) {

    // Parse arguments
    jsize argc = env->GetArrayLength(args_obj);
    char* argv[16];
    int arg_count = 0;

    for (jsize i = 0; i < argc && arg_count < 15; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(args_obj, i));
        const char* str = env->GetStringUTFChars(js, nullptr);
        argv[arg_count] = strdup(str);
        env->ReleaseStringUTFChars(js, str);
        env->DeleteLocalRef(js);
        arg_count++;
    }
    argv[arg_count] = nullptr;

    // Parse args: W H DPI PHONE_HOST EW EH FPS
    if (arg_count >= 7) {
        g_config.display_w = atoi(argv[0]);
        g_config.display_h = atoi(argv[1]);
        g_config.display_dpi = atoi(argv[2]);
        std::strncpy(g_config.phone_host, argv[3], sizeof(g_config.phone_host) - 1);
        g_config.encode_w = atoi(argv[4]);
        g_config.encode_h = atoi(argv[5]);
        g_config.fps = atoi(argv[6]);
    }

    // Initialize JNI bridge
    if (!jni::init_bridge(env, bridge_obj)) {
        LOGE("Failed to init JNI bridge");
        for (int i = 0; i < arg_count; ++i) free(argv[i]);
        return -1;
    }

    // Initialize Java bridge (for VD creation etc.)
    // Create VirtualDisplay via JNI up-call (need a Surface object from native)
    // The surface comes from EGL init in the pipeline thread.

    LOGI("Daemon config: VD=%dx%d@%ddpi encode=%dx%d@%dfps host=%s",
         g_config.display_w, g_config.display_h, g_config.display_dpi,
         g_config.encode_w, g_config.encode_h, g_config.fps,
         g_config.phone_host);

    // ── Start TCP servers for car ──
    if (!g_video_server.listen(9638)) {
        LOGE("Failed to listen on video port 9638");
        for (int i = 0; i < arg_count; ++i) free(argv[i]);
        return -1;
    }

    if (!g_input_server.listen(9639)) {
        LOGE("Failed to listen on input port 9639");
        g_video_server.close_all();
        for (int i = 0; i < arg_count; ++i) free(argv[i]);
        return -1;
    }

    // ── Connect lifecycle channel to phone app ──
    LifecycleChannel lifecycle;
    lifecycle.connect(g_config.lifecycle_path);

    // ── Start pipeline on a dedicated thread ──
    PipelineConfig pipe_cfg;
    pipe_cfg.display_width = g_config.display_w;
    pipe_cfg.display_height = g_config.display_h;
    pipe_cfg.display_dpi = g_config.display_dpi;
    pipe_cfg.encode_width = g_config.encode_w;
    pipe_cfg.encode_height = g_config.encode_h;
    pipe_cfg.fps = g_config.fps;
    pipe_cfg.phone_host = g_config.phone_host;

    // Accept car connections in parallel with pipeline start
    LOGI("Waiting for car connections...");

    // Accept video connection
    if (!g_video_server.accept(30000)) {
        LOGE("Timeout waiting for car video connection");
        lifecycle.send(0xFF); // error
        for (int i = 0; i < arg_count; ++i) free(argv[i]);
        return -1;
    }

    // Accept input connection
    if (!g_input_server.accept(30000)) {
        LOGE("Timeout waiting for car input connection");
        lifecycle.send(0xFF);
        for (int i = 0; i < arg_count; ++i) free(argv[i]);
        return -1;
    }

    LOGI("Car connected on video and input");

    // Set car connections on pipeline
    g_pipeline.set_car_video(&g_video_server);
    g_pipeline.set_car_input(&g_input_server);

    // ── Start touch reader thread ──
    TouchHandler touch_handler(g_config.display_w, g_config.display_h);
    touch_handler.set_inject_callback(touch_inject_callback,
                                       reinterpret_cast<void*>(static_cast<intptr_t>(0)));
    pthread_t touch_thread;
    pthread_create(&touch_thread, nullptr, touch_reader_thread, &touch_handler);

    // Start lifecycle reader thread
    pthread_t lc_thread;
    pthread_create(&lc_thread, nullptr, lifecycle_reader_thread, &lifecycle);

    // ── Run pipeline (blocks until stopped) ──
    LOGI("Starting pipeline loop...");
    int result = g_pipeline.run(pipe_cfg);

    // Cleanup
    g_pipeline.stop();
    pthread_join(touch_thread, nullptr);
    pthread_join(lc_thread, nullptr);

    g_video_server.close_all();
    g_input_server.close_all();
    lifecycle.close_channel();

    for (int i = 0; i < arg_count; ++i) free(argv[i]);

    LOGI("Daemon exiting with code %d", result);
    return result;
}

// JNI_OnLoad
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    jni::g_jvm = vm;
    LOGI("libdilinkd loaded");
    return JNI_VERSION_1_6;
}
