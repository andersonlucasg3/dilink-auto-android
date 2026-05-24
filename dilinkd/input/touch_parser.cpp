#include "touch_parser.h"

namespace dilink {

TouchHandler::TouchHandler(int display_w, int display_h)
    : display_w_(display_w), display_h_(display_h) {}

int TouchHandler::handle_frame(const protocol::Frame& frame) {
    if (frame.channel != protocol::CHANNEL_INPUT || frame.payload == nullptr) {
        return 0;
    }

    switch (frame.msg_type) {
        case protocol::INPUT_TOUCH_DOWN:
        case protocol::INPUT_TOUCH_MOVE:
        case protocol::INPUT_TOUCH_UP: {
            protocol::TouchEvent ev;
            if (protocol::decode_touch_event(frame.payload, frame.payload_size, ev)) {
                int action = (frame.msg_type == protocol::INPUT_TOUCH_DOWN) ? 0 :
                             (frame.msg_type == protocol::INPUT_TOUCH_UP)   ? 2 : 1;
                inject(ev, action);
                return 1;
            }
            break;
        }
        case protocol::INPUT_TOUCH_MOVE_BATCH: {
            protocol::TouchMoveBatch batch;
            if (protocol::decode_touch_move_batch(frame.payload, frame.payload_size, batch)) {
                inject_batch(batch);
                return batch.count;
            }
            break;
        }
    }

    return 0;
}

void TouchHandler::inject(const protocol::TouchEvent& ev, int action) {
    if (!inject_cb_) return;

    int x = static_cast<int>(ev.x * display_w_);
    int y = static_cast<int>(ev.y * display_h_);
    inject_cb_(action, x, y, ev.pointer_id, ev.pressure, inject_user_data_);
}

void TouchHandler::inject_batch(const protocol::TouchMoveBatch& batch) {
    if (!inject_cb_) return;

    for (uint8_t i = 0; i < batch.count; ++i) {
        const auto& p = batch.pointers[i];
        int x = static_cast<int>(p.x * display_w_);
        int y = static_cast<int>(p.y * display_h_);
        inject_cb_(1 /* MOVE */, x, y, p.pointer_id, p.pressure, inject_user_data_);
    }
}

} // namespace dilink
