// Input injection via IInputManager — JNI up-call to Java bridge.
// This is called from the touch reader thread (not pipeline thread).
// Implementation routes through bridge.cpp: inject_motion_event()
//
// The touch injection flow:
// 1. Car sends touch events via TCP (channel INPUT, port 9639)
// 2. Touch reader thread decodes wire protocol (protocol.cpp)
// 3. TouchHandler computes display coordinates
// 4. JNI up-call to NativeBridge.injectMotionEvent() which uses IInputManager reflection
//
// This file is a placeholder — the actual injection uses the bridge object from bridge.cpp.
