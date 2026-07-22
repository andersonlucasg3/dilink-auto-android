#include "bridge.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "dilinkd.JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {
namespace jni {

JavaVM* g_jvm = nullptr;

// Cached references to bridge object and methods
static jobject g_bridge_obj = nullptr;
static jclass  g_bridge_class = nullptr;
static jmethodID g_create_vd_method = nullptr;
static jmethodID g_display_power_method = nullptr;
static jmethodID g_exec_shell_method = nullptr;
static jmethodID g_launch_app_method = nullptr;
static jmethodID g_update_tex_method = nullptr;

bool init_bridge(JNIEnv* env, jobject bridge_obj) {
    // Create global reference to prevent GC
    g_bridge_obj = env->NewGlobalRef(bridge_obj);
    if (!g_bridge_obj) {
        LOGE("Failed to create global ref for bridge");
        return false;
    }

    jclass cls = env->GetObjectClass(bridge_obj);
    g_bridge_class = static_cast<jclass>(env->NewGlobalRef(cls));

    g_create_vd_method = env->GetMethodID(g_bridge_class, "createVirtualDisplayFromTexture",
                                           "(IIII)I");
    g_display_power_method = env->GetMethodID(g_bridge_class, "setDisplayPower",
                                               "(Z)Z");
    g_exec_shell_method = env->GetMethodID(g_bridge_class, "execShell",
                                            "(Ljava/lang/String;)Ljava/lang/String;");
    g_launch_app_method = env->GetMethodID(g_bridge_class, "launchApp",
                                            "(ILjava/lang/String;)Z");
    g_update_tex_method = env->GetMethodID(g_bridge_class, "updateTexImage", "()V");

    if (!g_create_vd_method || !g_display_power_method ||
        !g_exec_shell_method || !g_launch_app_method || !g_update_tex_method) {
        LOGE("Failed to find bridge methods");
        return false;
    }

    LOGI("Bridge initialized");
    return true;
}

int create_virtual_display(JNIEnv* env, int width, int height, int dpi,
                            uint32_t tex_id) {
    if (!g_create_vd_method || !g_bridge_obj) return -1;

    jint id = env->CallIntMethod(g_bridge_obj, g_create_vd_method,
                                  tex_id, width, height, dpi);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return -1;
    }
    return id;
}

bool set_display_power(JNIEnv* env, bool on) {
    if (!g_display_power_method || !g_bridge_obj) return false;

    jboolean result = env->CallBooleanMethod(g_bridge_obj, g_display_power_method,
                                              on ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return result == JNI_TRUE;
}

std::string exec_shell(JNIEnv* env, const std::string& cmd) {
    if (!g_exec_shell_method || !g_bridge_obj) return {};

    jstring jcmd = env->NewStringUTF(cmd.c_str());
    jstring result = static_cast<jstring>(
        env->CallObjectMethod(g_bridge_obj, g_exec_shell_method, jcmd));
    env->DeleteLocalRef(jcmd);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return {};
    }

    if (!result) return {};

    const char* str = env->GetStringUTFChars(result, nullptr);
    std::string out(str);
    env->ReleaseStringUTFChars(result, str);
    env->DeleteLocalRef(result);
    return out;
}

void update_tex_image(JNIEnv* env) {
    if (!g_update_tex_method || !g_bridge_obj) return;
    env->CallVoidMethod(g_bridge_obj, g_update_tex_method);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

bool launch_app(JNIEnv* env, int display_id, const std::string& package_name) {
    if (!g_launch_app_method || !g_bridge_obj) return false;

    jstring jpkg = env->NewStringUTF(package_name.c_str());
    jboolean result = env->CallBooleanMethod(g_bridge_obj, g_launch_app_method,
                                              display_id, jpkg);
    env->DeleteLocalRef(jpkg);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return result == JNI_TRUE;
}

} // namespace jni
} // namespace dilink
