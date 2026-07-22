#pragma once
#include <cstdint>
#include <atomic>
#include <jni.h>
#include "../encoder/amc_encoder.h"
#include "../encoder/bitrate_ctrl.h"
#include "../renderer/egl_core.h"
#include "../renderer/texture_blit.h"
#include "../network/tcp_stream.h"

namespace dilink {

struct PipelineConfig {
    int display_width;
    int display_height;
    int display_dpi;
    int encode_width;
    int encode_height;
    int fps;
    const char* phone_host; // IP of phone (for lifecycle unix socket)
};

// Single-threaded pipeline: frame clock → GL render → encoder drain → TCP write.
// All on one pthread. No queues between stages. Flow control is natural:
// if TCP stalls, the pipeline blocks, delaying next eglSwapBuffers.
class Pipeline {
public:
    Pipeline();
    ~Pipeline();

    Pipeline(const Pipeline&) = delete;
    Pipeline& operator=(const Pipeline&) = delete;

    // Phase 1: Initialize encoder + EGL + GL texture for SurfaceTexture.
    // Returns the GL texture ID. Caller creates SurfaceTexture(texId) + Surface
    // in Java, then creates VirtualDisplay with that Surface.
    GLuint init(const PipelineConfig& config);

    // Phase 2: Run the pipeline loop. env is for JNI up-calls (updateTexImage).
    int run_loop(JNIEnv* env);

    // Signal stop from another thread.
    void stop();

    // Whether the pipeline is actively running.
    bool is_running() const { return running_; }
    bool is_initialized() const { return initialized_; }

    // Set car connections (set by main thread after accept).
    void set_car_video(TcpStream* stream) { car_video_ = stream; }
    void set_car_input(TcpStream* stream) { car_input_ = stream; }

private:
    struct TouchState {
        int32_t pointer_id;
        float   x;
        float   y;
        float   pressure;
    };

    bool init_egl_and_encoder(const PipelineConfig& cfg,
                               ANativeWindow* encoder_surface);
    void cleanup();

    // Frame clock
    int64_t frame_interval_ns_ = 0;

    // Pipeline stages (all on pipeline thread)
    AmcEncoder encoder_;
    BitrateController bitrate_ctrl_;
    EglCore egl_;
    TextureBlit blit_;

    // Car connections (set by main thread before pipeline starts)
    TcpStream* car_video_ = nullptr;
    TcpStream* car_input_ = nullptr;

    // Internal state
    std::atomic<bool> running_{true};
    bool initialized_ = false;
    int prev_bitrate_ = 0;
};

} // namespace dilink
