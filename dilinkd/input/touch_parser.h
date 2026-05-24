#pragma once
#include "../network/protocol.h"

namespace dilink {

// Parses and handles incoming touch events from the car (channel=INPUT).
// Touch events arrive on the car input TCP connection.
// Coordinates are normalized (0.0-1.0) and mapped to display dimensions.

class TouchHandler {
public:
    TouchHandler(int display_w, int display_h);

    // Process a single frame received on the INPUT channel.
    // Calls the provided callback for each touch event that needs injection.
    // Returns number of touch events processed.
    int handle_frame(const protocol::Frame& frame);

    // Callback type: (action, display_x, display_y, pointer_id, pressure)
    // action: 0=DOWN, 1=MOVE, 2=UP
    using InjectCallback = void (*)(int action, int x, int y,
                                     int pointer_id, float pressure,
                                     void* user_data);

    void set_inject_callback(InjectCallback cb, void* user_data) {
        inject_cb_ = cb;
        inject_user_data_ = user_data;
    }

private:
    int display_w_;
    int display_h_;
    InjectCallback inject_cb_ = nullptr;
    void* inject_user_data_ = nullptr;

    void inject(const protocol::TouchEvent& ev, int action);
    void inject_batch(const protocol::TouchMoveBatch& batch);
};

} // namespace dilink
