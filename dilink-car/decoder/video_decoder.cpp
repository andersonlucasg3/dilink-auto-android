#include "video_decoder.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "dilink-car.VideoDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {
namespace car {

VideoDecoder::VideoDecoder() = default;

VideoDecoder::~VideoDecoder() { stop(); }

bool VideoDecoder::start(ANativeWindow* output_surface,
                          const uint8_t* config_data, size_t config_size,
                          int width, int height) {
    codec_ = AMediaCodec_createDecoderByType("video/avc");
    if (!codec_) {
        LOGE("Failed to create AVC decoder");
        return false;
    }

    // Cache config for replay
    if (config_size > 0 && config_size <= sizeof(config_data_)) {
        std::memcpy(config_data_, config_data, config_size);
        config_size_ = config_size;
    }

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
    AMediaFormat_setInt32(format, "low-latency", 1);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PRIORITY, 0); // real-time

    media_status_t status = AMediaCodec_configure(codec_, format,
                                                   output_surface, nullptr, 0);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        AMediaFormat_delete(format);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    surface_ = output_surface;
    AMediaFormat_delete(format);

    status = AMediaCodec_start(codec_);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    running_.store(true);
    started_ = true;

    // Feed cached config so decoder is ready for the first frame
    if (config_size_ > 0) {
        feed_frame(config_data_, config_size_, true);
        drain_output(); // process the config
    }

    LOGI("Decoder started: %dx%d", width, height);
    return true;
}

void VideoDecoder::feed_frame(const uint8_t* data, size_t size, bool is_keyframe) {
    if (!codec_ || !running_.load()) return;

    ssize_t in_idx = AMediaCodec_dequeueInputBuffer(codec_, 5000); // 5ms timeout
    if (in_idx < 0) {
        // No input buffer available — drop frame (decoder is backed up)
        return;
    }

    size_t buf_size = 0;
    uint8_t* buf = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(in_idx), &buf_size);
    if (buf && size <= buf_size) {
        std::memcpy(buf, data, size);

        uint32_t flags = 0;
        if (is_keyframe) flags |= AMEDIACODEC_BUFFER_FLAG_KEY_FRAME;

        AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(in_idx),
                                      0, size, 0, flags);
    } else {
        // Buffer too small — queue empty buffer to avoid starvation
        AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(in_idx),
                                      0, 0, 0, 0);
    }
}

bool VideoDecoder::set_output_surface(ANativeWindow* new_surface) {
    if (!codec_ || !new_surface) return false;

    media_status_t status = AMediaCodec_setOutputSurface(codec_, new_surface);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_setOutputSurface failed: %d", status);
        return false;
    }

    // Release old surface, hold new one
    if (surface_) {
        ANativeWindow_release(surface_);
    }
    surface_ = new_surface;
    ANativeWindow_acquire(surface_);

    LOGI("Decoder output surface switched");
    return true;
}

int VideoDecoder::drain_output() {
    if (!codec_ || !running_.load()) return 0;

    int rendered = 0;
    AMediaCodecBufferInfo info;

    while (true) {
        ssize_t out_idx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0); // non-blocking
        if (out_idx < 0) break;

        if (out_idx >= 0) {
            if (info.size > 0) {
                AMediaCodec_releaseOutputBuffer(codec_,
                    static_cast<size_t>(out_idx), true); // render=true → Surface
                rendered++;
            } else {
                AMediaCodec_releaseOutputBuffer(codec_,
                    static_cast<size_t>(out_idx), false);
            }
        }
    }

    return rendered;
}

void VideoDecoder::stop() {
    running_.store(false);
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    surface_ = nullptr;
    started_ = false;
}

} // namespace car
} // namespace dilink
