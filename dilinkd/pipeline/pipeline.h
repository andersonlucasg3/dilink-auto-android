#pragma once
#include <cstdint>
#include "../encoder/amc_encoder.h"
#include "../encoder/bitrate_ctrl.h"
#include "../renderer/egl_core.h"
#include "../renderer/texture_blit.h"
#include "../renderer/frame_diff.h"
#include "../network/tcp_stream.h"
#include "../network/pacing.h"
#include "frame_pool.h"

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

    // Start the pipeline. Blocks until stopped (runs on calling thread).
    // Returns 0 on clean exit, -1 on fatal error.
    int run(const PipelineConfig& config);

    // Signal stop from another thread.
    void stop();

    // Whether the pipeline is actively running.
    bool is_running() const { return running_; }

    // Set car connections (set by main thread after accept).
    void set_car_video(TcpStream* stream) { car_video_ = stream; }
    void set_car_input(TcpStream* stream) { car_input_ = stream; }

    // Touch injection callback (called from touch reader thread).
    // The pipeline thread handles frame encoding; touch injection happens
    // on a separate touch reader thread via JNI up-call.
    // These are set by main() after pipeline starts.

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
    FrameDiff frame_diff_;
    FramePool frame_pool_;

    // Car connections (set by main thread before pipeline starts)
    TcpStream* car_video_ = nullptr;
    TcpStream* car_input_ = nullptr;

    // Internal state
    volatile bool running_ = true;
    bool initialized_ = false;
};

} // namespace dilink
