#include "car_input.h"
#include "car_tcp.h"
#include "protocol.h"
#include <cstring>
#include <ctime>

namespace dilink {
namespace car {

static int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1'000'000LL;
}

bool CarInput::send_touch(uint8_t msg_type, int32_t pointer_id,
                           float norm_x, float norm_y, float pressure) {
    if (!tcp_ || !tcp_->is_connected()) return false;

    // Encode touch payload (25 bytes)
    protocol::encode_touch_event(buf_ + protocol::HEADER_SIZE,
                                  msg_type, pointer_id,
                                  norm_x, norm_y, pressure, now_ms());

    // Encode frame header
    protocol::encode_frame(buf_, protocol::CHANNEL_INPUT,
                            msg_type, nullptr, 25);

    return tcp_->write_all(buf_, protocol::HEADER_SIZE + 25);
}

bool CarInput::send_down(int x, int y, int pointer_id, float pressure) {
    float nx = (display_w_ > 0) ? static_cast<float>(x) / display_w_ : 0.0f;
    float ny = (display_h_ > 0) ? static_cast<float>(y) / display_h_ : 0.0f;
    return send_touch(protocol::INPUT_TOUCH_DOWN, pointer_id, nx, ny, pressure);
}

bool CarInput::send_move(int x, int y, int pointer_id, float pressure) {
    float nx = (display_w_ > 0) ? static_cast<float>(x) / display_w_ : 0.0f;
    float ny = (display_h_ > 0) ? static_cast<float>(y) / display_h_ : 0.0f;
    return send_touch(protocol::INPUT_TOUCH_MOVE, pointer_id, nx, ny, pressure);
}

bool CarInput::send_up(int x, int y, int pointer_id, float pressure) {
    float nx = (display_w_ > 0) ? static_cast<float>(x) / display_w_ : 0.0f;
    float ny = (display_h_ > 0) ? static_cast<float>(y) / display_h_ : 0.0f;
    return send_touch(protocol::INPUT_TOUCH_UP, pointer_id, nx, ny, pressure);
}

bool CarInput::send_move_batch(const int* pointer_ids, const float* xs,
                                const float* ys, const float* pressures,
                                int count) {
    if (!tcp_ || !tcp_->is_connected() || count < 1 || count > 10) return false;

    // Encode batch payload: 1B count + N × (4B id + 4B x + 4B y + 4B pressure + 8B timestamp)
    uint8_t* p = buf_ + protocol::HEADER_SIZE;
    p[0] = static_cast<uint8_t>(count);
    size_t off = 1;
    int64_t ts = now_ms();

    auto w32 = [&](int32_t v) { p[off] = (v>>24)&0xFF; p[off+1]=(v>>16)&0xFF; p[off+2]=(v>>8)&0xFF; p[off+3]=v&0xFF; off+=4; };
    auto wf = [&](float v) { uint32_t bits; std::memcpy(&bits, &v, 4); w32(static_cast<int32_t>(bits)); };
    auto w64 = [&](int64_t v) { w32(static_cast<int32_t>(v>>32)); w32(static_cast<int32_t>(v&0xFFFFFFFF)); };

    for (int i = 0; i < count; ++i) {
        w32(pointer_ids[i]);
        wf((display_w_ > 0) ? xs[i] / display_w_ : xs[i]);
        wf((display_h_ > 0) ? ys[i] / display_h_ : ys[i]);
        wf(pressures ? pressures[i] : 1.0f);
        w64(ts);
    }

    size_t payload_sz = off;
    protocol::encode_frame(buf_, protocol::CHANNEL_INPUT,
                            protocol::INPUT_TOUCH_MOVE_BATCH, nullptr, payload_sz);

    return tcp_->write_all(buf_, protocol::HEADER_SIZE + payload_sz);
}

} // namespace car
} // namespace dilink
