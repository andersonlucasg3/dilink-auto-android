#include "protocol.h"
#include <cstring>

namespace dilink {
namespace protocol {

size_t encode_frame(uint8_t* buffer, uint8_t channel, uint8_t msg_type,
                    const uint8_t* payload, size_t payload_size) {
    uint32_t frame_len = 2 + static_cast<uint32_t>(payload_size);
    buffer[0] = static_cast<uint8_t>((frame_len >> 24) & 0xFF);
    buffer[1] = static_cast<uint8_t>((frame_len >> 16) & 0xFF);
    buffer[2] = static_cast<uint8_t>((frame_len >> 8) & 0xFF);
    buffer[3] = static_cast<uint8_t>(frame_len & 0xFF);
    buffer[4] = channel;
    buffer[5] = msg_type;
    if (payload_size > 0 && payload) {
        std::memcpy(buffer + HEADER_SIZE, payload, payload_size);
    }
    return HEADER_SIZE + payload_size;
}

size_t decode_frame(const uint8_t* buffer, size_t buffer_size, Frame& out) {
    if (buffer_size < HEADER_SIZE) return 0;
    uint32_t frame_len =
        (static_cast<uint32_t>(buffer[0]) << 24) |
        (static_cast<uint32_t>(buffer[1]) << 16) |
        (static_cast<uint32_t>(buffer[2]) << 8)  |
        static_cast<uint32_t>(buffer[3]);
    if (frame_len < 2) return 0;
    size_t total = HEADER_SIZE + (frame_len - 2);
    if (buffer_size < total) return 0;
    out.length = frame_len;
    out.channel = buffer[4];
    out.msg_type = buffer[5];
    out.payload_size = frame_len - 2;
    out.payload = (out.payload_size > 0) ? buffer + HEADER_SIZE : nullptr;
    return total;
}

bool decode_touch_event(const uint8_t* payload, size_t size, TouchEvent& out) {
    if (size < 25) return false;
    size_t off = 0;
    out.action = payload[off++];
    auto r32 = [&]() { int32_t v = (static_cast<int32_t>(payload[off]) << 24) | (static_cast<int32_t>(payload[off+1]) << 16) | (static_cast<int32_t>(payload[off+2]) << 8) | static_cast<int32_t>(payload[off+3]); off += 4; return v; };
    auto rf = [&]() { uint32_t b = (static_cast<uint32_t>(payload[off]) << 24) | (static_cast<uint32_t>(payload[off+1]) << 16) | (static_cast<uint32_t>(payload[off+2]) << 8) | static_cast<uint32_t>(payload[off+3]); off += 4; float f; std::memcpy(&f, &b, sizeof(f)); return f; };
    auto r64 = [&]() { int64_t v = (static_cast<int64_t>(payload[off]) << 56) | (static_cast<int64_t>(payload[off+1]) << 48) | (static_cast<int64_t>(payload[off+2]) << 40) | (static_cast<int64_t>(payload[off+3]) << 32) | (static_cast<int64_t>(payload[off+4]) << 24) | (static_cast<int64_t>(payload[off+5]) << 16) | (static_cast<int64_t>(payload[off+6]) << 8) | static_cast<int64_t>(payload[off+7]); off += 8; return v; };
    out.pointer_id = r32();
    out.x = rf(); out.y = rf(); out.pressure = rf();
    out.timestamp = r64();
    return true;
}

bool decode_touch_move_batch(const uint8_t* payload, size_t size, TouchMoveBatch& out) {
    if (size < 1) return false;
    out.count = payload[0];
    if (out.count > 10) return false;
    const size_t ps = 4+4+4+4+8; // 24 bytes per pointer
    if (size - 1 < out.count * ps) return false;
    size_t off = 1;
    for (uint8_t i = 0; i < out.count; ++i) {
        auto r32b = [&]() { int32_t v = (static_cast<int32_t>(payload[off]) << 24) | (static_cast<int32_t>(payload[off+1]) << 16) | (static_cast<int32_t>(payload[off+2]) << 8) | static_cast<int32_t>(payload[off+3]); off += 4; return v; };
        auto rfb = [&]() { uint32_t bits = (static_cast<uint32_t>(payload[off]) << 24) | (static_cast<uint32_t>(payload[off+1]) << 16) | (static_cast<uint32_t>(payload[off+2]) << 8) | static_cast<uint32_t>(payload[off+3]); off += 4; float f; std::memcpy(&f, &bits, sizeof(f)); return f; };
        auto r64b = [&]() { int64_t v = (static_cast<int64_t>(payload[off]) << 56) | (static_cast<int64_t>(payload[off+1]) << 48) | (static_cast<int64_t>(payload[off+2]) << 40) | (static_cast<int64_t>(payload[off+3]) << 32) | (static_cast<int64_t>(payload[off+4]) << 24) | (static_cast<int64_t>(payload[off+5]) << 16) | (static_cast<int64_t>(payload[off+6]) << 8) | static_cast<int64_t>(payload[off+7]); off += 8; return v; };
        out.pointers[i].pointer_id = r32b();
        out.pointers[i].x = rfb(); out.pointers[i].y = rfb();
        out.pointers[i].pressure = rfb();
        out.pointers[i].timestamp = r64b();
    }
    return true;
}

size_t encode_touch_event(uint8_t* buffer, uint8_t msg_type,
                           int32_t pointer_id, float x, float y,
                           float pressure, int64_t timestamp) {
    buffer[0] = msg_type;
    size_t off = 1;
    auto w32 = [&](int32_t v) { buffer[off] = (v >> 24) & 0xFF; buffer[off+1] = (v >> 16) & 0xFF; buffer[off+2] = (v >> 8) & 0xFF; buffer[off+3] = v & 0xFF; off += 4; };
    auto wf = [&](float v) { uint32_t bits; std::memcpy(&bits, &v, 4); w32(static_cast<int32_t>(bits)); };
    auto w64 = [&](int64_t v) { w32(static_cast<int32_t>(v >> 32)); w32(static_cast<int32_t>(v & 0xFFFFFFFF)); };
    w32(pointer_id); wf(x); wf(y); wf(pressure); w64(timestamp);
    return 25; // 1B action + 4B id + 4B x + 4B y + 4B pressure + 8B timestamp
}

size_t encode_launch_app(uint8_t* buffer, const char* package_name) {
    size_t len = std::strlen(package_name);
    std::memcpy(buffer, package_name, len);
    return len;
}

} // namespace protocol
} // namespace dilink
