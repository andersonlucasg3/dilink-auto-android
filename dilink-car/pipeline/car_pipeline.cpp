#include "car_pipeline.h"
#include "protocol.h"
#include <android/log.h>
#include <sys/epoll.h>
#include <unistd.h>
#include <ctime>
#include <cstring>

#define LOG_TAG "dilink-car.Pipeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {
namespace car {

CarPipeline::CarPipeline() = default;

CarPipeline::~CarPipeline() { stop(); }

int CarPipeline::start(int video_port, int input_port,
                        ANativeWindow* output_surface,
                        int display_w, int display_h, int encode_w, int encode_h) {
    encode_w_ = encode_w;
    encode_h_ = encode_h;

    // Listen for daemon connections (reversed direction — daemon connects to car)
    if (!video_tcp_.listen(video_port)) {
        LOGE("Failed to listen on :%d for video", video_port);
        return -1;
    }
    if (!input_tcp_.listen(input_port)) {
        LOGE("Failed to listen on :%d for input", input_port);
        video_tcp_.close_all();
        return -1;
    }
    LOGI("Listening for daemon on :%d + :%d...", video_port, input_port);

    if (!video_tcp_.accept(60000)) {
        LOGE("Daemon video connect timeout (60s)");
        video_tcp_.close_all();
        input_tcp_.close_all();
        return -1;
    }
    if (!input_tcp_.accept(60000)) {
        LOGE("Daemon input connect timeout (60s)");
        video_tcp_.close_all();
        input_tcp_.close_all();
        return -1;
    }
    LOGI("Daemon connected on both video and input ports");

    // Init touch encoder
    input_encoder_.init(&input_tcp_, display_w, display_h);

    running_.store(true);

    // Start epoll reader thread
    if (pthread_create(&epoll_thread_, nullptr, epoll_reader_thread, this) != 0) {
        LOGE("Failed to create epoll thread");
        running_.store(false);
        epoll_thread_ = 0;
        video_tcp_.close_all();
        input_tcp_.close_all();
        return -1;
    }

    // Wait for CONFIG frame
    int wait_ms = 0;
    while (running_.load() && queue_.empty() && wait_ms < 5000) {
        struct timespec ts = {0, 10'000'000};
        nanosleep(&ts, nullptr);
        wait_ms += 10;
    }

    if (queue_.empty()) {
        LOGE("Timeout waiting for CONFIG frame");
        running_.store(false);
        pthread_join(epoll_thread_, nullptr); epoll_thread_ = 0;
        video_tcp_.close_all();
        input_tcp_.close_all();
        return -1;
    }

    // Cache config data
    const FrameQueue::Slot* config = queue_.peek();
    if (!config || !config->is_config) {
        LOGE("First frame is not CONFIG");
        running_.store(false);
        pthread_join(epoll_thread_, nullptr); epoll_thread_ = 0;
        video_tcp_.close_all();
        input_tcp_.close_all();
        return -1;
    }

    if (config->size > sizeof(cached_config_)) {
        LOGE("CONFIG too large: %zu > %zu", config->size, sizeof(cached_config_));
        running_.store(false);
        pthread_join(epoll_thread_, nullptr); epoll_thread_ = 0;
        video_tcp_.close_all();
        input_tcp_.close_all();
        return -1;
    }
    std::memcpy(cached_config_, config->data, config->size);
    cached_config_size_ = config->size;
    queue_.consume();
    LOGI("Config cached: %zu bytes, waiting for output surface", cached_config_size_);

    // If surface already available, start decoder now
    if (output_surface) {
        if (!decoder_.start(output_surface, cached_config_, cached_config_size_,
                             encode_w_, encode_h_)) {
            LOGE("Failed to start decoder");
            running_.store(false);
            pthread_join(epoll_thread_, nullptr); epoll_thread_ = 0;
            video_tcp_.close_all();
            input_tcp_.close_all();
            return -1;
        }

        // Start decoder thread
        if (pthread_create(&decoder_thread_, nullptr, decoder_thread, this) != 0) {
            LOGE("Failed to create decoder thread");
            running_.store(false);
            decoder_thread_ = 0;
            pthread_join(epoll_thread_, nullptr); epoll_thread_ = 0;
            video_tcp_.close_all();
            input_tcp_.close_all();
            return -1;
        }
    }

    LOGI("Car pipeline started: %d×%d, ports video:%d + input:%d (decoder %s)",
         encode_w_, encode_h_, video_port, input_port,
         output_surface ? "started" : "deferred");
    return 0;
}

void CarPipeline::stop() {
    running_.store(false);
    decoder_.signal_stop();
    video_tcp_.close_conn();
    input_tcp_.close_conn();

    if (epoll_thread_ != 0) {
        pthread_join(epoll_thread_, nullptr);
        epoll_thread_ = 0;
    }
    if (decoder_thread_ != 0) {
        pthread_join(decoder_thread_, nullptr);
        decoder_thread_ = 0;
    }

    decoder_.stop();
}

bool CarPipeline::set_surface(ANativeWindow* surface) {
    if (!surface) return false;

    // First surface — start decoder now with cached config
    if (!decoder_.started()) {
        if (cached_config_size_ == 0) {
            LOGE("Cannot start decoder: no cached config");
            return false;
        }
        if (!decoder_.start(surface, cached_config_, cached_config_size_,
                             encode_w_, encode_h_)) {
            LOGE("Failed to start decoder on delayed surface");
            return false;
        }
        // Start decoder thread
        if (pthread_create(&decoder_thread_, nullptr, decoder_thread, this) != 0) {
            LOGE("Failed to create decoder thread (delayed)");
            decoder_.stop();
            decoder_thread_ = 0;
            return false;
        }
        LOGI("Decoder started with delayed surface: %dx%d", encode_w_, encode_h_);
        return true;
    }

    // Decoder already running — switch output surface
    return decoder_.set_output_surface(surface);
}

void CarPipeline::send_touch_down(int x, int y, int pointer_id, float pressure) {
    input_encoder_.send_down(x, y, pointer_id, pressure);
}
void CarPipeline::send_touch_move(int x, int y, int pointer_id, float pressure) {
    input_encoder_.send_move(x, y, pointer_id, pressure);
}
void CarPipeline::send_touch_up(int x, int y, int pointer_id, float pressure) {
    input_encoder_.send_up(x, y, pointer_id, pressure);
}

void* CarPipeline::epoll_reader_thread(void* arg) {
    static_cast<CarPipeline*>(arg)->epoll_loop();
    return nullptr;
}

void* CarPipeline::decoder_thread(void* arg) {
    static_cast<CarPipeline*>(arg)->decoder_loop();
    return nullptr;
}

void CarPipeline::epoll_loop() {
    int epfd = epoll_create1(EPOLL_CLOEXEC);

    epoll_event ev;
    ev.events = EPOLLIN;
    ev.data.fd = video_tcp_.fd();
    epoll_ctl(epfd, EPOLL_CTL_ADD, video_tcp_.fd(), &ev);

    uint8_t recv_buf[65536];
    size_t recv_off = 0;

    while (running_.load()) {
        epoll_event events[1];
        int n = epoll_wait(epfd, events, 1, 100); // 100ms timeout

        if (n < 0) break; // error
        if (n == 0) continue; // timeout — check running

        ssize_t r = video_tcp_.read_some(recv_buf + recv_off,
                                          sizeof(recv_buf) - recv_off);
        if (r < 0) break; // EOF or error
        if (r > 0) recv_off += static_cast<size_t>(r);

        // Decode frames from buffer
        size_t consumed = 0;
        while (consumed < recv_off) {
            protocol::Frame frame;
            size_t flen = protocol::decode_frame(recv_buf + consumed,
                                                   recv_off - consumed, frame);
            if (flen == 0) break; // incomplete

            if (frame.channel == protocol::CHANNEL_VIDEO && frame.payload) {
                bool is_config = (frame.msg_type == protocol::VIDEO_CONFIG);
                bool is_key = is_config; // config is always kept

                // For FRAME type, check if it contains an IDR (keyframe)
                // Simple heuristic: if payload starts with NAL type 5 (IDR) or type 7 (SPS)
                if (!is_config && frame.payload_size > 4) {
                    // Check for NAL unit type in the first NAL
                    uint8_t nal_type = frame.payload[4] & 0x1F;
                    is_key = (nal_type == 5 || nal_type == 7);
                }

                queue_.push(frame.payload, frame.payload_size, is_key, is_config);
                if (is_config && !decoder_.is_running()) {
                    // Config received before decoder started — handled by start()
                }
                frame_count_.fetch_add(1);
            }

            consumed += flen;
        }

        if (consumed > 0 && consumed < recv_off) {
            std::memmove(recv_buf, recv_buf + consumed, recv_off - consumed);
        }
        recv_off -= consumed;
    }

    close(epfd);
    LOGI("Epoll reader exited, frames received: %lld", (long long)frame_count_.load());
}

void CarPipeline::decoder_loop() {
    while (running_.load()) {
        // Wait for output surface before feeding frames
        if (!decoder_.has_surface()) {
            struct timespec ts = {0, 10'000'000}; // 10ms
            nanosleep(&ts, nullptr);
            continue;
        }

        const FrameQueue::Slot* slot = queue_.peek();
        if (!slot) {
            struct timespec ts = {0, 2'000'000}; // 2ms
            nanosleep(&ts, nullptr);
            decoder_.drain_output();
            continue;
        }

        decoder_.feed_frame(slot->data, slot->size, slot->is_keyframe);
        queue_.consume();
        decoder_.drain_output();
    }
    LOGI("Decoder thread exited");
}

} // namespace car
} // namespace dilink
