#include "pipeline.h"
#include "../network/protocol.h"
#include "../jni/bridge.h"
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <ctime>
#include <cstring>

#define LOG_TAG "dilinkd.Pipeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {

static PipelineConfig g_cfg;
static int64_t now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1'000'000'000L + ts.tv_nsec;
}

Pipeline::Pipeline() = default;
Pipeline::~Pipeline() { cleanup(); }
void Pipeline::stop() { running_ = false; }

GLuint Pipeline::init(const PipelineConfig& config) {
    g_cfg = config;
    frame_interval_ns_ = 1'000'000'000L / config.fps;

    // ── Start encoder ──
    EncoderConfig enc_cfg;
    enc_cfg.width = config.encode_width;
    enc_cfg.height = config.encode_height;
    enc_cfg.fps = config.fps;
    enc_cfg.bitrate = 5'000'000;
    enc_cfg.i_frame_interval = 1;

    ANativeWindow* encoder_surface = nullptr;
    if (!encoder_.start(enc_cfg, encoder_surface)) {
        LOGE("Failed to start encoder");
        return 0;
    }

    // ── Init EGL + GL on this thread ──
    if (!egl_.init(encoder_surface, config.encode_width, config.encode_height)) {
        LOGE("Failed to init EGL");
        return 0;
    }

    // Create GL texture for SurfaceTexture (caller creates ST in Java)
    GLuint input_tex = 0;
    ANativeWindow* vd_surface_dummy = nullptr;
    if (!egl_.create_input_texture(config.display_width, config.display_height,
                                    input_tex, vd_surface_dummy)) {
        LOGE("Failed to create input texture");
        return 0;
    }

    blit_.init(egl_.program(), egl_.pos_loc(), egl_.tex_loc(),
               input_tex, config.display_width, config.display_height);

    initialized_ = true;
    LOGI("Pipeline init done: %dx%d VD -> %dx%d encode @%dfps, tex=%u",
         config.display_width, config.display_height,
         config.encode_width, config.encode_height, config.fps, input_tex);
    return input_tex;
}

int Pipeline::run_loop(JNIEnv* env) {
    if (!initialized_) {
        LOGE("Pipeline not initialized");
        return -1;
    }

    LOGI("Pipeline waiting for car video connection...");
    while (running_ && car_video_ == nullptr) {
        struct timespec ts = {0, 50'000'000};
        nanosleep(&ts, nullptr);
    }
    if (!running_) return 0;

    LOGI("Pipeline starting: %dx%d @%dfps",
         g_cfg.encode_width, g_cfg.encode_height, g_cfg.fps);

    // ── Pipeline Loop ──
    int64_t next_frame_ns = now_ns();
    int64_t frame_count = 0;
    int64_t keyframe_count = 0;
    int64_t last_log_at = 0;

    while (running_) {
        int64_t wait_ns = next_frame_ns - now_ns();
        if (wait_ns > 0) {
            struct timespec ts = {
                static_cast<time_t>(wait_ns / 1'000'000'000L),
                static_cast<long>(wait_ns % 1'000'000'000L)
            };
            nanosleep(&ts, nullptr);
        }
        next_frame_ns += frame_interval_ns_;
        if (next_frame_ns <= now_ns()) {
            next_frame_ns = now_ns() + frame_interval_ns_;
        }

        // Update GL texture from SurfaceTexture (JNI up-call to Java)
        if (env) jni::update_tex_image(env);

        egl_.begin_frame();
        blit_.render();
        egl_.swap_buffers();

        while (true) {
            AMediaCodecBufferInfo info;
            int idx = encoder_.dequeue_output(info);
            if (idx < 0) break;

            if (info.size > 0) {
                size_t buf_size = 0;
                uint8_t* buf = encoder_.get_output_buffer(idx, buf_size);
                if (buf && buf_size > 0) {
                    bool is_config = AmcEncoder::is_config(info.flags);
                    bool is_key = AmcEncoder::is_keyframe(info.flags);
                    if (is_key) keyframe_count++;

                    if (car_video_ && car_video_->is_connected()) {
                        uint8_t frame_hdr[protocol::HEADER_SIZE];
                        protocol::encode_frame_header(frame_hdr, protocol::CHANNEL_VIDEO,
                            is_config ? protocol::VIDEO_CONFIG : protocol::VIDEO_FRAME,
                            static_cast<size_t>(info.size));

                        int64_t write_start_ns = now_ns();
                        bool ok = car_video_->write_all(frame_hdr, protocol::HEADER_SIZE);
                        if (ok) ok = car_video_->write_all(buf, static_cast<size_t>(info.size));

                        if (ok) {
                            int64_t write_time_us = (now_ns() - write_start_ns) / 1000;
                            int new_br = bitrate_ctrl_.report_write_time(write_time_us);
                            if (new_br != prev_bitrate_) {
                                encoder_.set_bitrate(new_br);
                                prev_bitrate_ = new_br;
                            }
                            frame_count++;
                        } else {
                            LOGE("TCP write failed (frame %lld)", (long long)frame_count);
                        }
                    }
                }
            }
            encoder_.release_output(idx, false);
        }

        if (frame_count - last_log_at >= 120) {
            last_log_at = frame_count;
            LOGI("Frames: %lld, %dMbps, keys: %lld",
                 (long long)frame_count, bitrate_ctrl_.bitrate() / 1'000'000,
                 (long long)keyframe_count);
        }
    }

    LOGI("Pipeline exited: %lld frames, %lld keyframes",
         (long long)frame_count, (long long)keyframe_count);
    return 0;
}

void Pipeline::cleanup() {
    running_ = false;
    encoder_.stop();
    egl_.destroy();
    initialized_ = false;
}

} // namespace dilink
