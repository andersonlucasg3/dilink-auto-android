#pragma once
#include <cstdint>
#include <cstddef>
#include <atomic>
#include <cstring>

namespace dilink {
namespace car {

// Lock-free SPSC frame queue. 3 slots — minimal latency.
// Producer: epoll reader thread. Consumer: decoder thread.
// Keyframes always accepted (evict oldest P-frame if full).
// P-frames dropped when queue is full.
class FrameQueue {
public:
    static constexpr size_t MAX_FRAME = 512 * 1024; // 512KB per frame
    static constexpr size_t SLOTS = 3;

    struct Slot {
        uint8_t data[MAX_FRAME];
        size_t  size = 0;
        bool    is_keyframe = false;
        bool    is_config = false;
        bool    ready = false; // consumer reads, producer writes
    };

    FrameQueue() = default;

    // Producer: push a decoded frame. Returns false if dropped (not a keyframe).
    bool push(const uint8_t* data, size_t size, bool is_keyframe, bool is_config) {
        if (size > MAX_FRAME) return false;

        if (count_.load() >= SLOTS) {
            if (!is_keyframe && !is_config) return false; // drop P-frame
            // Keyframe/config: evict from tail
            pop_consume();
        }

        Slot& slot = slots_[write_idx_];
        std::memcpy(slot.data, data, size);
        slot.size = size;
        slot.is_keyframe = is_keyframe;
        slot.is_config = is_config;
        slot.ready = true;

        write_idx_ = (write_idx_ + 1) % SLOTS;
        count_.fetch_add(1);
        return true;
    }

    // Consumer: get the next ready frame. Returns nullptr if queue empty.
    // Frame data is valid until next call to consume().
    const Slot* peek() {
        if (count_.load() == 0) return nullptr;
        Slot& slot = slots_[read_idx_.load(std::memory_order_relaxed)];
        if (!slot.ready) return nullptr;
        return &slot;
    }

    // Consumer: mark current frame as consumed.
    void consume() {
        size_t r = read_idx_.load(std::memory_order_relaxed);
        slots_[r].ready = false;
        read_idx_.store((r + 1) % SLOTS, std::memory_order_relaxed);
        count_.fetch_sub(1, std::memory_order_relaxed);
    }

    // Consumer: consume without peeking (used internally).
    void pop_consume() {
        if (count_.load(std::memory_order_relaxed) == 0) return;
        size_t r = read_idx_.load(std::memory_order_relaxed);
        slots_[r].ready = false;
        read_idx_.store((r + 1) % SLOTS, std::memory_order_relaxed);
        count_.fetch_sub(1, std::memory_order_relaxed);
    }

    size_t count() const { return count_.load(std::memory_order_relaxed); }
    bool empty() const { return count_.load(std::memory_order_relaxed) == 0; }

private:
    Slot slots_[SLOTS];
    std::atomic<size_t> count_{0};
    size_t write_idx_ = 0;
    std::atomic<size_t> read_idx_{0};
};

} // namespace car
} // namespace dilink
