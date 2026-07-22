#pragma once
#include <cstdint>
#include <cstddef>

namespace dilink {
namespace car {

// Encodes and sends touch events from car to phone daemon.
// Wire format matches protocol: 6-byte header + 25-byte TouchEvent payload.
// Uses the pre-connected CarTcp for sending.
class CarInput {
public:
    CarInput() = default;

    // Set the TCP connection and display dimensions for coordinate scaling.
    void init(class CarTcp* tcp, int display_w, int display_h) {
        tcp_ = tcp; display_w_ = display_w; display_h_ = display_h;
    }

    // Send a touch DOWN event. Coordinates in display pixels.
    bool send_down(int x, int y, int pointer_id = 0, float pressure = 1.0f);

    // Send a touch MOVE event.
    bool send_move(int x, int y, int pointer_id = 0, float pressure = 1.0f);

    // Send a touch UP event.
    bool send_up(int x, int y, int pointer_id = 0, float pressure = 1.0f);

    // Send a batch of MOVE events for multi-touch.
    bool send_move_batch(const int* pointer_ids, const float* xs,
                          const float* ys, const float* pressures,
                          int count);

private:
    bool send_touch(uint8_t msg_type, int32_t pointer_id,
                     float norm_x, float norm_y, float pressure);

    CarTcp* tcp_ = nullptr;
    int display_w_ = 0;
    int display_h_ = 0;

    // Pre-allocated send buffer: header + max batch payload (1B count + 10 * 24B per pointer)
    uint8_t buf_[6 + 1 + 10 * 24];
};

} // namespace car
} // namespace dilink
