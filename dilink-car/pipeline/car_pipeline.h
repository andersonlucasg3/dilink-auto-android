#pragma once
#include "../decoder/video_decoder.h"
#include "../network/car_tcp.h"
#include "../network/car_input.h"
#include "frame_queue.h"
#include <pthread.h>
#include <atomic>

namespace dilink {
namespace car {

// Car-side pipeline: TCP → frame decode → queue → decoder → Surface.
// Two threads: epoll reader + decoder.
//
// epoll reader thread:
//   epoll_wait on video fd → read frame → decode header → push to FrameQueue
//
// decoder thread:
//   peek FrameQueue → feed decoder → drain output → consume
class CarPipeline {
public:
    CarPipeline();
    ~CarPipeline();

    CarPipeline(const CarPipeline&) = delete;
    CarPipeline& operator=(const CarPipeline&) = delete;

    // Start the pipeline. Connects to daemon on video_port and input_port.
    // output_surface: ANativeWindow from TextureView.
    // Returns 0 on success, -1 on failure.
    int start(int video_port, int input_port,
              ANativeWindow* output_surface,
              int display_w, int display_h, int encode_w, int encode_h);

    // Stop the pipeline. Blocks until threads exit.
    void stop();

    // Switch decoder output surface (e.g., offscreen → TextureView).
    // Thread-safe — may be called while pipeline is running.
    bool set_surface(ANativeWindow* surface);

    // Touch injection (called from JNI → native from Compose on UI thread).
    // These are thread-safe — the input connection is dedicated to touch.
    void send_touch_down(int x, int y, int pointer_id, float pressure);
    void send_touch_move(int x, int y, int pointer_id, float pressure);
    void send_touch_up(int x, int y, int pointer_id, float pressure);

    // State queries.
    bool is_running() const { return running_.load(); }
    bool has_received_frame() const { return frame_count_ > 0; }
    int64_t frame_count() const { return frame_count_; }

private:
    static void* epoll_reader_thread(void* arg);
    static void* decoder_thread(void* arg);
    void epoll_loop();
    void decoder_loop();

    VideoDecoder decoder_;
    CarTcp       video_tcp_;
    CarTcp       input_tcp_;
    CarInput     input_encoder_;
    FrameQueue   queue_;

    std::atomic<bool> running_{false};
    std::atomic<int64_t> frame_count_{0};

    int encode_w_ = 0;
    int encode_h_ = 0;

    pthread_t epoll_thread_ = 0;
    pthread_t decoder_thread_ = 0;
};

} // namespace car
} // namespace dilink
