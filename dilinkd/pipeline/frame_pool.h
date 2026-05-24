#pragma once
#include <cstddef>
#include <cstdint>
#include <atomic>

namespace dilink {

// Pre-allocated frame buffer pool. No allocations in the hot path.
// Single-producer (pipeline thread drains encoder output into a buffer)
// Single-consumer (network thread sends buffer to TCP).
// In our single-threaded pipeline, producer == consumer, so no synchronization needed.
class FramePool {
public:
    static constexpr size_t MAX_FRAME_SIZE = 1024 * 1024; // 1MB
    static constexpr size_t POOL_SIZE = 8;

    FramePool();
    ~FramePool();

    FramePool(const FramePool&) = delete;
    FramePool& operator=(const FramePool&) = delete;

    // Get a buffer for writing encoder output. Returns nullptr if pool exhausted.
    uint8_t* acquire();

    // Return size of the last acquire()'d data (set after filling).
    void commit(size_t size);

    // Get the most recently committed buffer and its size.
    const uint8_t* data() const { return buffers_[read_idx_]; }
    size_t size() const { return sizes_[read_idx_]; }

    // Advance read pointer (call after TCP send completes).
    void release();

private:
    uint8_t* buffers_[POOL_SIZE];
    size_t   sizes_[POOL_SIZE];
    size_t   write_idx_ = 0;
    size_t   read_idx_ = 0;
    size_t   available_ = POOL_SIZE;
};

} // namespace dilink
