#pragma once
#include <media/NdkMediaCodec.h>
#include <android/native_window.h>
#include <cstdint>
#include <atomic>

namespace dilink {
namespace car {

// H.264 video decoder using AMediaCodec with Surface output.
// Renders directly to TextureView Surface — zero GPU copy.
// Runs on its own pthread. Single consumer of the frame queue.
class VideoDecoder {
public:
    VideoDecoder();
    ~VideoDecoder();

    VideoDecoder(const VideoDecoder&) = delete;
    VideoDecoder& operator=(const VideoDecoder&) = delete;

    // Start the decoder. output_surface: ANativeWindow from TextureView SurfaceTexture.
    // config_data: SPS/PPS NAL units (CONFIG frame payload).
    // Returns false if the codec can't be configured.
    bool start(ANativeWindow* output_surface,
               const uint8_t* config_data, size_t config_size,
               int width, int height);

    // Feed a video frame (H.264 NAL units) to the decoder.
    // Non-blocking — queues internally, returns immediately.
    // is_keyframe: true if this is an IDR frame.
    void feed_frame(const uint8_t* data, size_t size, bool is_keyframe);

    // Switch output surface (e.g., offscreen → TextureView).
    // Uses AMediaCodec_setOutputSurface — no decoder restart needed.
    bool set_output_surface(ANativeWindow* new_surface);

    // Drain output from the decoder. Call periodically.
    // Returns number of frames rendered (usually 0 or 1).
    int drain_output();

    // Stop and release the decoder.
    void stop();

    // Signal from another thread to shut down.
    void signal_stop() { running_.store(false); }
    bool is_running() const { return running_.load(); }

private:
    AMediaCodec* codec_ = nullptr;
    ANativeWindow* surface_ = nullptr;
    std::atomic<bool> running_{false};
    bool started_ = false;

    // Cached CONFIG for replay on codec reset
    uint8_t config_data_[256];
    size_t  config_size_ = 0;
};

} // namespace car
} // namespace dilink
