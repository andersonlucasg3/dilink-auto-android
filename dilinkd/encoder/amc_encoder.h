#pragma once
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window.h>
#include <cstdint>

namespace dilink {

struct EncoderConfig {
    int width;
    int height;
    int fps;
    int bitrate;        // initial bitrate in bps (default 5_000_000)
    int i_frame_interval; // seconds between keyframes (default 1)
};

class AmcEncoder {
public:
    AmcEncoder();
    ~AmcEncoder();

    AmcEncoder(const AmcEncoder&) = delete;
    AmcEncoder& operator=(const AmcEncoder&) = delete;

    // Configure and start the encoder. Returns the input Surface for rendering.
    bool start(const EncoderConfig& config, ANativeWindow*& out_input_surface);

    // Stop and release the encoder.
    void stop();

    // Dequeue an encoded buffer. Returns index >= 0, or -1 if no output ready.
    // Caller reads data from the output buffer and calls release_output().
    int dequeue_output(AMediaCodecBufferInfo& info);

    // Get the output buffer at the given index.
    uint8_t* get_output_buffer(int index, size_t& out_size);

    // Release the output buffer back to the codec.
    void release_output(int index, bool render = false);

    // Dynamically update bitrate (for adaptive bitrate control).
    void set_bitrate(int bitrate_bps);

    // Request a sync frame (keyframe) at the next opportunity.
    void request_sync_frame();

    // Get current configuration.
    const EncoderConfig& config() const { return config_; }

    // Check if a buffer info indicates this is a codec config (SPS/PPS).
    static bool is_config(int flags) {
        return (flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0;
    }

    // Check if a buffer info indicates this is a keyframe.
    static bool is_keyframe(int flags) {
        return (flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME) != 0;
    }

    bool is_running() const { return codec_ != nullptr; }

private:
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;
    ANativeWindow* input_surface_ = nullptr;
    EncoderConfig config_;
};

} // namespace dilink
