#pragma once
#include <cstdint>
#include <cstddef>

// Wire-compatible with com.dilinkauto.protocol (FrameCodec.kt, Channel.kt, MessageType.kt)
// 6-byte header: 4B frame_length (BE) + 1B channel + 1B msg_type
// Shared between phone daemon (dilinkd) and car decoder (dilink-car).

namespace dilink {
namespace protocol {

constexpr uint8_t CHANNEL_CONTROL  = 0;
constexpr uint8_t CHANNEL_VIDEO    = 1;
constexpr uint8_t CHANNEL_AUDIO    = 2;
constexpr uint8_t CHANNEL_DATA     = 3;
constexpr uint8_t CHANNEL_INPUT    = 4;

constexpr uint8_t VIDEO_CONFIG = 1;
constexpr uint8_t VIDEO_FRAME  = 2;

constexpr uint8_t INPUT_TOUCH_DOWN      = 1;
constexpr uint8_t INPUT_TOUCH_MOVE      = 2;
constexpr uint8_t INPUT_TOUCH_UP        = 3;
constexpr uint8_t INPUT_TOUCH_MOVE_BATCH = 4;

constexpr uint8_t CONTROL_LAUNCH_APP    = 0x10;
constexpr uint8_t CONTROL_GO_HOME       = 0x11;
constexpr uint8_t CONTROL_GO_BACK       = 0x12;
constexpr uint8_t CONTROL_APP_UNINSTALL = 0x1B;
constexpr uint8_t CONTROL_APP_INFO      = 0x17;
constexpr uint8_t CONTROL_APP_SHORTCUTS = 0x18;

constexpr uint8_t MSG_DISPLAY_READY = 0x10;
constexpr uint8_t MSG_STACK_EMPTY   = 0x11;
constexpr uint8_t MSG_FOCUSED_APP   = 0x12;
constexpr uint8_t CMD_STOP          = 0xFF;

constexpr size_t HEADER_SIZE = 6;

struct Frame {
    uint32_t    length;
    uint8_t     channel;
    uint8_t     msg_type;
    const uint8_t* payload;
    size_t      payload_size;
};

// Touch event payload (25 bytes, big-endian)
struct TouchEvent {
    uint8_t  action;
    int32_t  pointer_id;
    float    x;
    float    y;
    float    pressure;
    int64_t  timestamp;
};

// Multi-touch batch pointer
struct TouchPointer {
    int32_t pointer_id;
    float   x;
    float   y;
    float   pressure;
    int64_t timestamp;
};

struct TouchMoveBatch {
    uint8_t      count;
    TouchPointer pointers[10];
};

size_t encode_frame(uint8_t* buffer, uint8_t channel, uint8_t msg_type,
                    const uint8_t* payload, size_t payload_size);
size_t decode_frame(const uint8_t* buffer, size_t buffer_size, Frame& out);

// Encode only the 6-byte frame header (no payload copy). Use when payload
// is sent separately (e.g., encoder output buffer sent in a second write).
inline void encode_frame_header(uint8_t* buffer, uint8_t channel, uint8_t msg_type,
                                size_t payload_size) {
    uint32_t frame_len = 2 + static_cast<uint32_t>(payload_size);
    buffer[0] = static_cast<uint8_t>((frame_len >> 24) & 0xFF);
    buffer[1] = static_cast<uint8_t>((frame_len >> 16) & 0xFF);
    buffer[2] = static_cast<uint8_t>((frame_len >> 8) & 0xFF);
    buffer[3] = static_cast<uint8_t>(frame_len & 0xFF);
    buffer[4] = channel;
    buffer[5] = msg_type;
}
bool decode_touch_event(const uint8_t* payload, size_t size, TouchEvent& out);
bool decode_touch_move_batch(const uint8_t* payload, size_t size, TouchMoveBatch& out);

// Encode a touch event into 25-byte payload (matches TouchEvent.kt wire format)
size_t encode_touch_event(uint8_t* buffer, uint8_t msg_type,
                           int32_t pointer_id, float x, float y,
                           float pressure, int64_t timestamp);

// Encode a launch-app message payload (UTF-8 package name)
size_t encode_launch_app(uint8_t* buffer, const char* package_name);

} // namespace protocol
} // namespace dilink
