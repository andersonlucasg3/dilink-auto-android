#include "amc_encoder.h"
#include <android/log.h>
#include <media/NdkMediaFormat.h>

#define LOG_TAG "dilinkd.AmcEncoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {

AmcEncoder::AmcEncoder() = default;

AmcEncoder::~AmcEncoder() {
    stop();
}

bool AmcEncoder::start(const EncoderConfig& config, ANativeWindow*& out_input_surface) {
    config_ = config;

    codec_ = AMediaCodec_createEncoderByType("video/avc");
    if (!codec_) {
        LOGE("Failed to create AVC encoder");
        return false;
    }

    format_ = AMediaFormat_new();
    AMediaFormat_setString(format_, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_WIDTH, config.width);
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_HEIGHT, config.height);
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_COLOR_FORMAT,
                          21); // COLOR_FormatSurface
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_BIT_RATE, config.bitrate);
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_FRAME_RATE, config.fps);
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, config.i_frame_interval);
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_BITRATE_MODE,
                          2); // BITRATE_MODE_CBR

    // Main profile: better compression (~20% vs Baseline) at same bitrate
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_PROFILE,
                          8); // AVCProfileMain = 8

    // Lowest latency
    AMediaFormat_setInt32(format_, "latency", 0);
    AMediaFormat_setInt32(format_, AMEDIAFORMAT_KEY_PRIORITY, 0); // real-time
    AMediaFormat_setInt32(format_, "max-bframes", 0); // no B-frames

    // Repeat previous frame on static content for up to 100ms
    AMediaFormat_setInt64(format_, "repeat-previous-frame-after", 100000);

    media_status_t status = AMediaCodec_configure(codec_, format_, nullptr, nullptr,
                                                   AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        AMediaFormat_delete(format_);
        format_ = nullptr;
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    // Create input surface for GPU rendering
    status = AMediaCodec_createInputSurface(codec_, &input_surface_);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_createInputSurface failed: %d", status);
        AMediaFormat_delete(format_);
        format_ = nullptr;
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    status = AMediaCodec_start(codec_);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        ANativeWindow_release(input_surface_);
        input_surface_ = nullptr;
        AMediaFormat_delete(format_);
        format_ = nullptr;
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    out_input_surface = input_surface_;
    LOGI("Encoder started: %dx%d @%dfps %dMbps Main CBR",
         config.width, config.height, config.fps, config.bitrate / 1000000);
    return true;
}

void AmcEncoder::stop() {
    if (codec_) {
        AMediaCodec_stop(codec_);
        if (input_surface_) {
            ANativeWindow_release(input_surface_);
            input_surface_ = nullptr;
        }
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (format_) {
        AMediaFormat_delete(format_);
        format_ = nullptr;
    }
}

int AmcEncoder::dequeue_output(AMediaCodecBufferInfo& info) {
    if (!codec_) return -1;
    ssize_t idx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0); // non-blocking
    if (idx >= 0) return static_cast<int>(idx);
    // FORMAT_CHANGED means output format is ready — get it, then retry
    if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat* fmt = AMediaCodec_getOutputFormat(codec_);
        if (fmt) AMediaFormat_delete(fmt);
        return -1; // caller will retry next time
    }
    return -1; // INFO_TRY_AGAIN_LATER or error
}

uint8_t* AmcEncoder::get_output_buffer(int index, size_t& out_size) {
    if (!codec_) return nullptr;
    return AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(index), &out_size);
}

void AmcEncoder::release_output(int index, bool render) {
    if (codec_) {
        AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(index), render);
    }
}

void AmcEncoder::set_bitrate(int bitrate_bps) {
    if (!codec_) return;

    AMediaFormat* params = AMediaFormat_new();
    AMediaFormat_setInt32(params, "video-bitrate", bitrate_bps);
    AMediaCodec_setParameters(codec_, params);
    AMediaFormat_delete(params);

    config_.bitrate = bitrate_bps;
}

void AmcEncoder::request_sync_frame() {
    if (!codec_) return;

    AMediaFormat* params = AMediaFormat_new();
    AMediaFormat_setInt32(params, "request-sync", 0);
    AMediaCodec_setParameters(codec_, params);
    AMediaFormat_delete(params);
}

} // namespace dilink
