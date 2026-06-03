// dilinkd — Native VirtualDisplay streaming daemon
//
// Launch: CLASSPATH=vd-server.jar app_process / com.dilinkauto.vdserver.DaemonEntry <args>
// DaemonEntry loads libdilinkd.so, creates NativeBridge, calls nativeRun().

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cstring>
#include <ctime>

#include "pipeline/pipeline.h"
#include "network/tcp_stream.h"
#include "network/protocol.h"
#include "input/touch_parser.h"
#include "lifecycle/unix_socket.h"
#include "jni/bridge.h"

#define LOG_TAG "dilinkd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace dilink;

static Pipeline g_pipeline;
static TcpStream g_video_server;
static TcpStream g_input_server;

struct DaemonConfig {
    int display_w = 1408, display_h = 792, display_dpi = 120;
    int encode_w = 1408, encode_h = 792, fps = 30;
    char phone_host[256] = "127.0.0.1";
    char car_host[256] = "";
};
static DaemonConfig g_config;

// ── Touch injection callback ──
static void touch_inject_callback(int action, int x, int y,
                                   int pointer_id, float pressure, void* user_data) {
    (void)pointer_id; (void)pressure;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (jni::g_jvm) {
        jint ret = jni::g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) { jni::g_jvm->AttachCurrentThread(&env, nullptr); attached = true; }
    }
    if (!env) return;
    char cmd[256];
    if (action == 0 || action == 1) {
        snprintf(cmd, sizeof(cmd), "input -d %d tap %d %d",
                 static_cast<int>(reinterpret_cast<intptr_t>(user_data)), x, y);
        jni::exec_shell(env, cmd);
    }
    if (attached) jni::g_jvm->DetachCurrentThread();
}

// ── Reader threads ──
static void* touch_reader_thread(void* arg) {
    auto* handler = static_cast<TouchHandler*>(arg);
    uint8_t recv_buf[65536]; size_t recv_off = 0;
    while (g_pipeline.is_running()) {
        ssize_t n = g_input_server.read_some(recv_buf + recv_off, sizeof(recv_buf) - recv_off);
        if (n < 0) break;
        if (n == 0) { struct timespec ts = {0, 5'000'000}; nanosleep(&ts, nullptr); continue; }
        recv_off += static_cast<size_t>(n);
        size_t consumed = 0;
        while (consumed < recv_off) {
            protocol::Frame frame;
            size_t flen = protocol::decode_frame(recv_buf + consumed, recv_off - consumed, frame);
            if (flen == 0) break;
            if (frame.channel == protocol::CHANNEL_INPUT) handler->handle_frame(frame);
            consumed += flen;
        }
        if (consumed > 0 && consumed < recv_off)
            std::memmove(recv_buf, recv_buf + consumed, recv_off - consumed);
        recv_off -= consumed;
    }
    LOGI("Touch reader exited");
    return nullptr;
}

static void* lifecycle_reader_thread(void* arg) {
    auto* lc = static_cast<LifecycleChannel*>(arg);
    while (g_pipeline.is_running()) {
        int cmd = lc->read_command(1000);
        if (cmd == protocol::CMD_STOP) { LOGI("CMD_STOP from phone"); g_pipeline.stop(); break; }
        if (cmd < 0 && !lc->is_connected()) {
            LOGI("Lifecycle channel lost");
            break;
        }
    }
    return nullptr;
}

// ── JNI: nativeRun ──
extern "C" JNIEXPORT jint JNICALL
Java_com_dilinkauto_vdserver_DaemonEntry_nativeRun(
    JNIEnv* env, jobject /* thiz */, jobjectArray args_obj, jobject bridge_obj) {

    // Parse args
    jsize argc = env->GetArrayLength(args_obj);
    char* argv[16]; int arg_count = 0;
    for (jsize i = 0; i < argc && arg_count < 15; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(args_obj, i));
        const char* str = env->GetStringUTFChars(js, nullptr);
        argv[arg_count] = strdup(str);
        env->ReleaseStringUTFChars(js, str); env->DeleteLocalRef(js);
        arg_count++;
    }

    if (arg_count >= 7) {
        g_config.display_w = atoi(argv[0]); g_config.display_h = atoi(argv[1]);
        g_config.display_dpi = atoi(argv[2]);
        std::strncpy(g_config.phone_host, argv[3], sizeof(g_config.phone_host) - 1);
        g_config.encode_w = atoi(argv[4]); g_config.encode_h = atoi(argv[5]);
        g_config.fps = atoi(argv[6]);
    }

    if (arg_count >= 8) {
        std::strncpy(g_config.car_host, argv[7], sizeof(g_config.car_host) - 1);
    }

    if (!jni::init_bridge(env, bridge_obj)) { LOGE("JNI bridge init failed"); return -1; }

    LOGI("Config: VD=%dx%d@%ddpi encode=%dx%d@%dfps host=%s car=%s",
         g_config.display_w, g_config.display_h, g_config.display_dpi,
         g_config.encode_w, g_config.encode_h, g_config.fps,
         g_config.phone_host, g_config.car_host);

    // ── Phase 1: Init pipeline (encoder + EGL + SurfaceTexture) ──
    PipelineConfig pipe_cfg;
    pipe_cfg.display_width = g_config.display_w;
    pipe_cfg.display_height = g_config.display_h;
    pipe_cfg.display_dpi = g_config.display_dpi;
    pipe_cfg.encode_width = g_config.encode_w;
    pipe_cfg.encode_height = g_config.encode_h;
    pipe_cfg.fps = g_config.fps;
    pipe_cfg.phone_host = g_config.phone_host;

    GLuint tex_id = g_pipeline.init(pipe_cfg);
    if (tex_id == 0) { LOGE("Pipeline init failed"); return -1; }

    // ── Phase 2: Create VirtualDisplay via JNI up-call ──
    int display_id = jni::create_virtual_display(env,
        g_config.display_w, g_config.display_h, g_config.display_dpi,
        tex_id);
    if (display_id < 0) {
        LOGE("VirtualDisplay creation failed");
        return -1;
    }
    LOGI("VirtualDisplay created: id=%d", display_id);

    // Turn off physical display, launch home, disable screen timeout
    jni::set_display_power(env, false);
    jni::exec_shell(env, "am start --display " + std::to_string(display_id) +
        " -a android.intent.action.MAIN -c android.intent.category.HOME");
    jni::exec_shell(env, "settings put system screen_off_timeout 2147483647");

    // ── Phase 3: Connect to phone lifecycle channel (TCP 127.0.0.1:19647) ──
    LifecycleChannel lifecycle;
    if (lifecycle.connect(g_config.phone_host, 19647)) {
        // Send MSG_DISPLAY_READY: 1 byte msg_type + 4 bytes display_id + 1 byte flags
        uint8_t ready_payload[5];
        ready_payload[0] = static_cast<uint8_t>((display_id >> 24) & 0xFF);
        ready_payload[1] = static_cast<uint8_t>((display_id >> 16) & 0xFF);
        ready_payload[2] = static_cast<uint8_t>((display_id >> 8) & 0xFF);
        ready_payload[3] = static_cast<uint8_t>(display_id & 0xFF);
        ready_payload[4] = 1; // hasDirectInjection = true
        lifecycle.send(0x10, ready_payload, 5); // MSG_DISPLAY_READY
        LOGI("Display ready sent to phone");
    } else {
        LOGI("Lifecycle channel not available — continuing without phone signaling");
    }

    // ── Phase 4: Connect to car (reverse direction — daemon is TCP client, outbound passes firewall) ──
    if (g_config.car_host[0] == '\0') {
        LOGE("car_host not provided -- cannot connect to car");
        return -1;
    }

    LOGI("Connecting to car %s for video (port 9638)...", g_config.car_host);
    for (int retry = 0; retry < 30; ++retry) {
        if (!g_pipeline.is_running()) { LOGE("Pipeline stopped before car connect"); return -1; }
        if (g_video_server.connect(g_config.car_host, 9638, 5000)) break;
        struct timespec ts = {1, 0}; nanosleep(&ts, nullptr);
    }
    if (!g_video_server.is_connected()) { LOGE("Car video connect failed after 30 retries"); return -1; }
    LOGI("Car video connection established");

    LOGI("Connecting to car %s for input (port 9639)...", g_config.car_host);
    for (int retry = 0; retry < 30; ++retry) {
        if (!g_pipeline.is_running()) { LOGE("Pipeline stopped before car connect"); return -1; }
        if (g_input_server.connect(g_config.car_host, 9639, 5000)) break;
        struct timespec ts = {1, 0}; nanosleep(&ts, nullptr);
    }
    if (!g_input_server.is_connected()) { LOGE("Car input connect failed after 30 retries"); return -1; }
    LOGI("Car input connection established");

    g_pipeline.set_car_video(&g_video_server);
    g_pipeline.set_car_input(&g_input_server);

    // ── Start reader threads ──
    TouchHandler touch_handler(g_config.display_w, g_config.display_h);
    touch_handler.set_inject_callback(touch_inject_callback,
        reinterpret_cast<void*>(static_cast<intptr_t>(display_id)));
    pthread_t touch_thread, lc_thread;
    pthread_create(&touch_thread, nullptr, touch_reader_thread, &touch_handler);
    if (lifecycle.is_connected()) {
        pthread_create(&lc_thread, nullptr, lifecycle_reader_thread, &lifecycle);
    }

    // ── Run pipeline loop ──
    LOGI("Starting pipeline loop...");
    int result = g_pipeline.run_loop(env);

    // Cleanup
    jni::set_display_power(env, true);
    jni::exec_shell(env, "input keyevent 224"); // wake screen
    g_pipeline.stop();
    pthread_join(touch_thread, nullptr);
    if (lifecycle.is_connected()) {
        pthread_join(lc_thread, nullptr);
    }
    g_video_server.close_all();
    g_input_server.close_all();

    for (int i = 0; i < arg_count; ++i) free(argv[i]);
    LOGI("Daemon exit: %d", result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    jni::g_jvm = vm;
    return JNI_VERSION_1_6;
}
