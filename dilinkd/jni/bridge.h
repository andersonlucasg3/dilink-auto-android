#pragma once
#include <jni.h>
#include <android/log.h>
#include <string>

namespace dilink {
namespace jni {

// Global references (initialized in JNI_OnLoad)
extern JavaVM* g_jvm;

// Java bridge class: com.dilinkauto.vdserver.NativeBridge
// Provides methods for VD creation, input injection, display power, shell commands.

// Initialize global references to the bridge object and its methods.
// Called once after the bridge object is created in Java.
bool init_bridge(JNIEnv* env, jobject bridge_obj);

// ── Bridge calls (these up-call to Java) ──

// Create a VirtualDisplay. Java creates SurfaceTexture(tex_id) + Surface + VD.
// Returns display ID, or -1 on failure.
int create_virtual_display(JNIEnv* env, int width, int height, int dpi,
                            uint32_t tex_id);

// Toggle physical display power.
bool set_display_power(JNIEnv* env, bool on);

// Execute a shell command. Returns output or nullptr.
std::string exec_shell(JNIEnv* env, const std::string& cmd);

// Launch an app on the virtual display.
bool launch_app(JNIEnv* env, int display_id, const std::string& package_name);

// Update the SurfaceTexture (call before each GL render).
void update_tex_image(JNIEnv* env);

} // namespace jni
} // namespace dilink
