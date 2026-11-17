package com.dilinkauto.protocol

/**
 * Wire protocol for the phone ↔ VD-server *lifecycle channel*
 * (localhost TCP, see [Ports.LIFECYCLE_PORT]).
 *
 * Deliberately a separate object from the [FrameCodec]/ControlMsg message
 * numbering: this channel exchanges raw single bytes / tiny fixed records, and
 * its values (0x10/0x11) intentionally overlap ControlMsg's LAUNCH_APP/GO_HOME
 * numbers. The two protocols are unrelated — sharing a numbering space would
 * create false coupling, so a future ControlMsg renumber must never be allowed
 * to silently change this wire format.
 *
 * Both ends of the channel live off these constants so a change can never land
 * on only one side:
 *  - server (vd-server `PipelineServer`): sends READY / STACK_EMPTY, reads STOP
 *  - phone (app-client `VirtualDisplayClient`): reads READY / STACK_EMPTY, sends STOP
 */
object VdLifecycle {

    /**
     * VD server → phone: the virtual display is up.
     * Payload: 4-byte displayId, then 1-byte flags (bit0 = direct touch injection).
     */
    const val MSG_DISPLAY_READY: Byte = 0x10

    /**
     * VD server → phone: the last launched app left the display (stack empty,
     * launcher resumed). No payload — length byte only.
     */
    const val MSG_STACK_EMPTY: Byte = 0x11

    /** Phone → VD server: graceful shutdown request (triggers running=false → cleanup()). */
    const val CMD_STOP: Int = 0xFF
}
