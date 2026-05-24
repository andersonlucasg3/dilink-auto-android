#include "frame_pool.h"
#include <cstdlib>
#include <cstring>

namespace dilink {

FramePool::FramePool() {
    for (size_t i = 0; i < POOL_SIZE; ++i) {
        buffers_[i] = static_cast<uint8_t*>(std::aligned_alloc(64, MAX_FRAME_SIZE));
        sizes_[i] = 0;
    }
}

FramePool::~FramePool() {
    for (size_t i = 0; i < POOL_SIZE; ++i) {
        std::free(buffers_[i]);
    }
}

uint8_t* FramePool::acquire() {
    if (available_ == 0) return nullptr;
    return buffers_[write_idx_];
}

void FramePool::commit(size_t size) {
    sizes_[write_idx_] = size;
    write_idx_ = (write_idx_ + 1) % POOL_SIZE;
    --available_;
}

void FramePool::release() {
    if (available_ < POOL_SIZE) {
        read_idx_ = (read_idx_ + 1) % POOL_SIZE;
        ++available_;
    }
}

} // namespace dilink
