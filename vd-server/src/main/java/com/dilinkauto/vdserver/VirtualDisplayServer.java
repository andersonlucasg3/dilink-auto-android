package com.dilinkauto.vdserver;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lightweight server that runs via app_process as shell UID (2000).
 * Creates a real VirtualDisplay, encodes its content as H.264,
 * and streams it over a local TCP socket.
 *
 * Shell UID can create virtual displays that host any activity.
 * Uses FakeContext (com.android.shell identity) for DisplayManager access,
 * following the same approach as scrcpy.
 *
 * Usage: CLASSPATH=<dex> app_process / com.dilinkauto.vdserver.VirtualDisplayServer <width> <height> <dpi> <port>
 */
public class VirtualDisplayServer {

    // Server → Client protocol
    private static final byte MSG_VIDEO_CONFIG = 0x01;
    private static final byte MSG_VIDEO_FRAME = 0x02;
    private static final byte MSG_DISPLAY_READY = 0x10;
    private static final byte MSG_STACK_EMPTY = 0x11;

    // Client → Server protocol
    private static final int CMD_LAUNCH_APP = 0x20;
    private static final int CMD_GO_BACK = 0x21;
    private static final int CMD_GO_HOME = 0x22;
    private static final int CMD_INPUT_TAP = 0x30;
    private static final int CMD_INPUT_SWIPE = 0x31;
    private static final int CMD_INPUT_TOUCH = 0x32;  // Raw MotionEvent injection (low-latency)
    private static final int CMD_STOP = 0xFF;

    private static final int BITRATE = 12_000_000;  // 12Mbps CBR — high quality for car viewport
    private static final int I_FRAME_INTERVAL = 1; // 1s IDR — faster recovery from corruption

    private final int width;      // VD resolution (large, for app layout)
    private final int height;
    private final int dpi;
    private final int encodeWidth; // Encoder resolution (car viewport, for streaming)
    private final int encodeHeight;
    private final int port;
    private final int fps;
    private final long frameIntervalMs;

    private int displayId = -1;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    // NIO write queue — lock-free enqueue from encoder thread, drained by writer thread
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();
    private volatile Thread writerThread;
    private SurfaceScaler scaler;
    private Thread scalerThread;
    private volatile boolean running = true;
    private String savedScreenOffTimeout = null;
    private String savedLiftWakeup = null;
    private String savedProximityWakeup = null;
    private long lastPowerOffTime = 0;
    // Persistent shell for fast input injection (avoids fork+exec per tap/swipe)
    private Process persistentShell;
    private java.io.OutputStream shellInput;
    // Direct MotionEvent injection via IInputManager (bypasses shell entirely)
    private Object inputManager;
    private Method injectInputEventMethod;
    private Method setDisplayIdMethod;
    // Multi-touch pointer state
    private final java.util.Map<Integer, float[]> activePointers = new java.util.LinkedHashMap<>();
    private long touchDownTime = 0;
    // Pre-allocated pools for MotionEvent construction — avoids per-touch GC pressure
    private static final int MAX_POINTERS = 10;
    private final android.view.MotionEvent.PointerProperties[] propsPool =
            new android.view.MotionEvent.PointerProperties[MAX_POINTERS];
    private final android.view.MotionEvent.PointerCoords[] coordsPool =
            new android.view.MotionEvent.PointerCoords[MAX_POINTERS];
    {
        for (int i = 0; i < MAX_POINTERS; i++) {
            propsPool[i] = new android.view.MotionEvent.PointerProperties();
            coordsPool[i] = new android.view.MotionEvent.PointerCoords();
        }
    }

    public static void main(String[] args) {
        int w = args.length > 0 ? Integer.parseInt(args[0]) : 1408;
        int h = args.length > 1 ? Integer.parseInt(args[1]) : 792;
        int d = args.length > 2 ? Integer.parseInt(args[2]) : 120;
        int p = args.length > 3 ? Integer.parseInt(args[3]) : 19637;
        int ew = args.length > 4 ? Integer.parseInt(args[4]) : w;
        int eh = args.length > 5 ? Integer.parseInt(args[5]) : h;
        int fps = args.length > 6 ? Integer.parseInt(args[6]) : 30;

        log("Starting: VD=" + w + "x" + h + " @" + d + "dpi, encode=" + ew + "x" + eh + ", port=" + p + ", fps=" + fps);
        new VirtualDisplayServer(w, h, d, p, ew, eh, fps).run();
    }

    public VirtualDisplayServer(int width, int height, int dpi, int port, int encodeWidth, int encodeHeight, int fps) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.encodeWidth = encodeWidth;
        this.encodeHeight = encodeHeight;
        this.port = port;
        this.fps = fps;
        this.frameIntervalMs = 1000L / fps;
    }

    private void run() {
        // ── Phase 1: Set up everything BEFORE connecting ──
        // InputManager for direct MotionEvent injection (scrcpy approach via ServiceManager)
        try {
            // Get IInputManager via ServiceManager — works in app_process (unlike InputManager.getInstance())
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
            Object inputBinder = getService.invoke(null, "input");

            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder.class);
            inputManager = asInterface.invoke(null, inputBinder);

            // injectInputEvent(InputEvent, int mode) — mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            injectInputEventMethod = inputManager.getClass().getDeclaredMethod("injectInputEvent",
                    android.view.InputEvent.class, int.class);
            injectInputEventMethod.setAccessible(true);

            // Cache setDisplayId for MotionEvent targeting
            try {
                setDisplayIdMethod = android.view.MotionEvent.class.getMethod("setDisplayId", int.class);
            } catch (Exception ex) {
                err("setDisplayId not available: " + ex.getMessage());
            }

            log("InputManager injection ready (ServiceManager/IInputManager)");
        } catch (Exception e) {
            err("InputManager init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }

        // Persistent shell fallback
        try {
            persistentShell = Runtime.getRuntime().exec(new String[]{"sh"});
            shellInput = persistentShell.getOutputStream();
        } catch (Exception e) {
            err("Failed to start persistent shell: " + e.getMessage());
        }

        // Create encoder and virtual display
        try {
            setupEncoder();
            createVirtualDisplay();
        } catch (Exception e) {
            err("Fatal: failed to create VD/encoder: " + e.getMessage());
            e.printStackTrace(System.err);
            return;
        }

        if (displayId < 0) {
            err("Failed to create virtual display");
            return;
        }

        // ── Phase 2: Connect TO the phone (reverse connection) ──
        // The phone's ConnectionService has a ServerSocket waiting for us on localhost:PORT.
        // By connecting now, the phone knows we're ready — no polling needed.
        // Retry loop only for initial connection (phone ServerSocket may not be open yet).
        // Uses NIO non-blocking connect — can check 'running' flag during connection attempts.
        boolean connected = false;
        for (int attempt = 0; attempt < 60 && running; attempt++) { // max 12s
            SocketChannel ch = null;
            try {
                log("Connecting to phone on localhost:" + port + " (attempt " + (attempt+1) + ")...");
                ch = SocketChannel.open();
                ch.configureBlocking(false);
                ch.connect(new InetSocketAddress("127.0.0.1", port));

                // Non-blocking connect — poll finishConnect so we can check 'running'
                long deadline = System.currentTimeMillis() + 2000;
                while (!ch.finishConnect()) {
                    if (!running || System.currentTimeMillis() > deadline) {
                        ch.close();
                        throw new java.net.ConnectException("timeout or stopped");
                    }
                    Thread.sleep(50);
                }

                log("Connected to phone");
                connected = true;
                handleClient(ch);
                // Phone disconnected — exit gracefully so a fresh VD server can be deployed.
                // No reconnect loop: the car will deploy a new VD server if needed.
                log("Phone disconnected — exiting");
                break;
            } catch (java.net.ConnectException e) {
                // Phone not ready yet — retry quickly
                if (ch != null) try { ch.close(); } catch (Exception ignored) {}
                try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
            } catch (Exception e) {
                if (ch != null) try { ch.close(); } catch (Exception ignored) {}
                err("Connection error: " + e.getMessage());
                break;
            }
        }

        if (!connected) {
            err("Could not connect to phone after retries — exiting");
        }

        cleanup();
    }

    private void handleClient(SocketChannel ch) {
        try {
            ch.configureBlocking(false);
            ch.socket().setTcpNoDelay(true);
            ch.socket().setSendBufferSize(262144);
            ch.socket().setReceiveBufferSize(262144);

            // Tell client the display is ready (direct write before threads start)
            ByteBuffer readyBuf = ByteBuffer.allocate(5);
            readyBuf.put(MSG_DISPLAY_READY);
            readyBuf.putInt(displayId);
            readyBuf.flip();
            writeAllBlocking(ch, readyBuf);
            log("Display ready: id=" + displayId + " " + width + "x" + height + "@" + dpi);

            // Launch home activity on VD so the encoder has content to encode.
            execShell("am start --display " + displayId +
                    " -a android.intent.action.MAIN -c android.intent.category.HOME");
            log("Home launched on display " + displayId);

            // Power off physical display while streaming via SurfaceControl (scrcpy approach).
            // More reliable than `cmd display power-off` — controls backlight directly
            // without affecting system sleep, encoder surfaces, or Wireless Debugging.
            setPhysicalDisplayPower(false);
            lastPowerOffTime = System.currentTimeMillis();

            // Start NIO writer thread — drains writeQueue to channel
            Thread wt = new Thread(() -> {
                log("Writer thread started");
                try {
                    runWriter(ch);
                } catch (Exception e) {
                    err("Writer thread CRASHED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                log("Writer thread exited (running=" + running + ")");
            }, "NioWriter");
            wt.setDaemon(true);
            wt.start();
            writerThread = wt;

            // Start video output thread — reads encoder, enqueues to writeQueue
            Thread videoThread = new Thread(() -> {
                log("VideoOutput thread started");
                try {
                    readEncoderOutput();
                } catch (Exception e) {
                    err("VideoOutput thread CRASHED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                }
                log("VideoOutput thread exited (running=" + running + ")");
            }, "VideoOutput");
            videoThread.setDaemon(true);
            videoThread.start();

            // Read commands from client (NIO Selector-based)
            readCommands(ch);

        } catch (IOException e) {
            err("Client error: " + e.getMessage());
        }
    }

    /** Blocking write for initial handshake before NIO threads start */
    private void writeAllBlocking(SocketChannel ch, ByteBuffer buf) throws IOException {
        ch.configureBlocking(true);
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
        ch.configureBlocking(false);
    }

    /** Enqueue data for non-blocking write. Thread-safe, lock-free. */
    private void enqueueWrite(byte msgType, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + data.length);
        buf.put(msgType);
        buf.putInt(data.length);
        buf.put(data);
        buf.flip();
        writeQueue.add(buf);
        Thread wt = writerThread;
        if (wt != null) java.util.concurrent.locks.LockSupport.unpark(wt);
    }

    /** Enqueue a single-byte message (e.g., MSG_STACK_EMPTY) */
    private void enqueueWriteByte(byte msgType) {
        ByteBuffer buf = ByteBuffer.allocate(1);
        buf.put(msgType);
        buf.flip();
        writeQueue.add(buf);
        Thread wt = writerThread;
        if (wt != null) java.util.concurrent.locks.LockSupport.unpark(wt);
    }

    /** NIO writer thread — drains writeQueue to channel, never blocks the encoder */
    private void runWriter(SocketChannel ch) throws IOException {
        long writeCount = 0;
        while (running) {
            ByteBuffer buf = writeQueue.poll();
            if (buf == null) {
                java.util.concurrent.locks.LockSupport.parkNanos(frameIntervalMs * 1_000_000L);
                continue;
            }
            while (buf.hasRemaining()) {
                int n = ch.write(buf);
                if (n == 0) {
                    if (!running) throw new IOException("Connection closed during write");
                    Thread.yield();
                }
            }
            writeCount++;
            if (writeCount % 60 == 0) {
                log("Writer: wrote " + writeCount + " messages, queue=" + writeQueue.size());
            }
        }
    }

    private void setupEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        // CBR for predictable bandwidth over WiFi (VBR causes bitrate spikes on keyframes)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        // High profile — better compression efficiency (more quality per bit), still no B-frames with CBR
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        // Low-latency mode — encoder returns frames ASAP
        format.setInteger(MediaFormat.KEY_LATENCY, 1);
        format.setInteger(MediaFormat.KEY_PRIORITY, 1);
        // Repeat previous frame if VD is static (prevents encoder stall on maps/menus)
        format.setLong("repeat-previous-frame-after", 1_000_000L); // 1 second

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            log("Encoder: " + encodeWidth + "x" + encodeHeight + " CBR@" + (BITRATE/1_000_000) + "Mbps High low-latency");
        } catch (Exception e) {
            throw new IOException("Failed to create encoder: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a VirtualDisplay.
     *
     * Strategy 1: Use DisplayManagerGlobal directly via reflection — bypasses
     *   DisplayManager.getDisplayIdToMirror() which NPEs on UserManager in app_process.
     * Strategy 2: Fallback to DisplayManager with mDisplayIdToMirror pre-set via reflection.
     */
    private void createVirtualDisplay() {
        Surface encoderSurface = encoder.createInputSurface();
        encoder.start();

        // If VD resolution differs from encoder, use GPU scaling via SurfaceScaler.
        // The VD renders at full resolution onto a SurfaceTexture, which is drawn
        // (GPU-scaled) onto the encoder Surface at car viewport resolution.
        Surface vdSurface;
        if (width != encodeWidth || height != encodeHeight) {
            log("GPU scaling: VD " + width + "x" + height + " → encoder " + encodeWidth + "x" + encodeHeight);
            scaler = new SurfaceScaler(encoderSurface, width, height, encodeWidth, encodeHeight, frameIntervalMs);
            scaler.start(); // launches GL thread, inits EGL on that thread
            vdSurface = scaler.getInputSurface(); // blocks until ready
            log("SurfaceScaler ready");
        } else {
            vdSurface = encoderSurface;
        }

        // Strategy 1: DisplayManagerGlobal.createVirtualDisplay() — bypasses UserManager entirely
        try {
            log("Trying DisplayManagerGlobal approach...");
            virtualDisplay = createVirtualDisplayViaGlobal(vdSurface);
            if (virtualDisplay != null) {
                displayId = virtualDisplay.getDisplay().getDisplayId();
                log("VirtualDisplay created via DisplayManagerGlobal: displayId=" + displayId);
                return;
            }
            err("DisplayManagerGlobal returned null VD");
        } catch (Exception e) {
            err("DisplayManagerGlobal failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }

        // Strategy 2: DisplayManager with mDisplayIdToMirror bypass
        try {
            log("Trying DisplayManager + mDisplayIdToMirror bypass...");
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
            ctor.setAccessible(true);
            DisplayManager dm = ctor.newInstance(FakeContext.get());

            // Pre-set mDisplayIdToMirror to DEFAULT_DISPLAY (0)
            try {
                Field displayIdField = DisplayManager.class.getDeclaredField("mDisplayIdToMirror");
                displayIdField.setAccessible(true);
                displayIdField.setInt(dm, 0);
                log("Set mDisplayIdToMirror=0");
            } catch (Exception e) {
                err("mDisplayIdToMirror reflection failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // Flags for a trusted, isolated virtual display that hosts third-party activities.
            // OWN_DISPLAY_GROUP and OWN_FOCUS are critical — without them, Android's task manager
            // treats the VD as part of the default display group and migrates activities to the
            // phone's physical screen.
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | (1 << 6)   // ROTATES_WITH_CONTENT
                    | (1 << 9)   // SHOULD_SHOW_SYSTEM_DECORATIONS
                    | (1 << 10)  // TRUSTED (requires ADD_TRUSTED_DISPLAY, shell has it on Android 13+)
                    | (1 << 11)  // OWN_DISPLAY_GROUP — isolates task stack from default display
                    | (1 << 13)  // ALWAYS_UNLOCKED
                    | (1 << 14); // OWN_FOCUS — prevents focus migration to physical display

            log("Creating VD with flags=0x" + Integer.toHexString(flags));
            virtualDisplay = dm.createVirtualDisplay(
                    "DiLinkAutoVD",
                    width, height, dpi,
                    vdSurface,
                    flags
            );

            if (virtualDisplay != null) {
                displayId = virtualDisplay.getDisplay().getDisplayId();
                log("VirtualDisplay created via DisplayManager: displayId=" + displayId);

                // Set IME policy via IWindowManager (not DisplayManager)
                // DISPLAY_IME_POLICY_LOCAL = 0 — show keyboard on this display
                setDisplayImePolicy(displayId);

                // Force apps to be resizable on the VD so they respect its density.
                execShell("settings put global force_resizable_activities 1");
                log("Enabled force_resizable_activities for VD");

                // Disable screen timeout so the phone doesn't autolock while streaming.
                // Save the current value to restore on cleanup.
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                            "settings get system screen_off_timeout"});
                    byte[] buf = new byte[64];
                    int len = p.getInputStream().read(buf);
                    p.waitFor();
                    savedScreenOffTimeout = (len > 0) ? new String(buf, 0, len).trim() : "60000";
                    execShell("settings put system screen_off_timeout 2147483647");
                    log("Screen timeout disabled (was " + savedScreenOffTimeout + "ms)");
                } catch (Exception e) {
                    err("Failed to disable screen timeout: " + e.getMessage());
                }

                // Disable proximity/lift wake so placing the phone down doesn't wake the screen
                try {
                    savedLiftWakeup = execShellOutput("settings get system lift_wakeup_enabled");
                    savedProximityWakeup = execShellOutput("settings get system proximity_wakeup_enabled");
                    execShell("settings put system lift_wakeup_enabled 0");
                    execShell("settings put system proximity_wakeup_enabled 0");
                    log("Lift/proximity wake disabled (was lift=" + savedLiftWakeup + " prox=" + savedProximityWakeup + ")");
                } catch (Exception e) {
                    err("Failed to disable wake sensors: " + e.getMessage());
                }

            } else {
                err("DisplayManager.createVirtualDisplay returned null");
            }
        } catch (Exception e) {
            err("DisplayManager approach failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    /**
     * Create a VirtualDisplay by calling DisplayManagerGlobal directly.
     * Builds a VirtualDisplayConfig and passes it to the global instance,
     * completely bypassing DisplayManager.getDisplayIdToMirror().
     */
    private VirtualDisplay createVirtualDisplayViaGlobal(Surface surface) throws Exception {
        // Get DisplayManagerGlobal singleton
        Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Method getInstance = dmgClass.getDeclaredMethod("getInstance");
        Object dmg = getInstance.invoke(null);
        log("Got DisplayManagerGlobal instance");

        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | (1 << 6)   // ROTATES_WITH_CONTENT
                | (1 << 9)   // SHOULD_SHOW_SYSTEM_DECORATIONS
                | (1 << 10)  // TRUSTED
                | (1 << 11)  // OWN_DISPLAY_GROUP
                | (1 << 13)  // ALWAYS_UNLOCKED
                | (1 << 14); // OWN_FOCUS

        // Build VirtualDisplayConfig
        Class<?> configBuilderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Constructor<?> builderCtor = configBuilderClass.getDeclaredConstructor(
                String.class, int.class, int.class, int.class);
        builderCtor.setAccessible(true);
        Object builder = builderCtor.newInstance("DiLinkAutoVD", width, height, dpi);

        // setFlags
        Method setFlags = configBuilderClass.getDeclaredMethod("setFlags", int.class);
        setFlags.setAccessible(true);
        setFlags.invoke(builder, flags);

        // setSurface
        Method setSurface = configBuilderClass.getDeclaredMethod("setSurface", Surface.class);
        setSurface.setAccessible(true);
        setSurface.invoke(builder, surface);

        // setDisplayIdToMirror(0) — DEFAULT_DISPLAY, bypasses UserManager
        try {
            Method setDisplayIdToMirror = configBuilderClass.getDeclaredMethod("setDisplayIdToMirror", int.class);
            setDisplayIdToMirror.setAccessible(true);
            setDisplayIdToMirror.invoke(builder, 0);
            log("VirtualDisplayConfig: displayIdToMirror=0");
        } catch (NoSuchMethodException e) {
            log("setDisplayIdToMirror not available (older API)");
        }

        // build()
        Method build = configBuilderClass.getDeclaredMethod("build");
        build.setAccessible(true);
        Object config = build.invoke(builder);
        log("VirtualDisplayConfig built");

        // Call DisplayManagerGlobal.createVirtualDisplay(VirtualDisplayConfig, callback, handler, packageName)
        Class<?> configClass = Class.forName("android.hardware.display.VirtualDisplayConfig");

        // Try different method signatures (varies by Android version)
        Method createVD = null;
        try {
            // Android 14+ signature
            createVD = dmgClass.getDeclaredMethod("createVirtualDisplay",
                    configClass, android.hardware.display.VirtualDisplay.Callback.class,
                    android.os.Handler.class, String.class);
        } catch (NoSuchMethodException e1) {
            try {
                // Android 12-13 signature (IVirtualDisplayCallback)
                Class<?> callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback");
                createVD = dmgClass.getDeclaredMethod("createVirtualDisplay",
                        configClass, callbackClass, String.class);
            } catch (NoSuchMethodException e2) {
                err("No suitable createVirtualDisplay method found on DisplayManagerGlobal");
                throw e2;
            }
        }

        createVD.setAccessible(true);
        log("Calling DisplayManagerGlobal.createVirtualDisplay...");
        Object result;
        if (createVD.getParameterCount() == 4) {
            result = createVD.invoke(dmg, config, null, null, FakeContext.get().getPackageName());
        } else {
            result = createVD.invoke(dmg, config, null, FakeContext.get().getPackageName());
        }
        return (VirtualDisplay) result;
    }

    private void readEncoderOutput() {
        log("readEncoderOutput: entering, encoder=" + (encoder != null) + " running=" + running);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long frameCount = 0;
        long keyFrameCount = 0;
        long noOutputCount = 0;
        long lastFrameTime = System.currentTimeMillis();
        long lastKeyFrameAt = 0;
        // Write buffer removed — frames are enqueued via non-blocking writeQueue

        while (running) {
            try {
                int outputIndex = encoder.dequeueOutputBuffer(info, frameIntervalMs * 1000);
                if (outputIndex >= 0) {
                    long now = System.currentTimeMillis();
                    long gap = now - lastFrameTime;

                    ByteBuffer buffer = encoder.getOutputBuffer(outputIndex);
                    if (buffer == null || info.size <= 0) {
                        // Empty output buffer — encoder returned a buffer with no data
                        noOutputCount++;
                        if (noOutputCount == 1 || noOutputCount == 10 || noOutputCount % 100 == 0) {
                            log("Empty output buffer #" + noOutputCount + ": index=" + outputIndex
                                + " size=" + info.size + " flags=0x" + Integer.toHexString(info.flags)
                                + " buffer=" + (buffer != null ? "non-null" : "null")
                                + " gap=" + (now - lastFrameTime) + "ms sent=" + frameCount);
                        }
                        encoder.releaseOutputBuffer(outputIndex, false);
                        continue;
                    }
                    lastFrameTime = now;
                    if (buffer != null && info.size > 0) {
                        boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        byte msgType = isConfig ? MSG_VIDEO_CONFIG : MSG_VIDEO_FRAME;
                        int size = info.size;

                        if (isKeyFrame) {
                            keyFrameCount++;
                            long sinceLast = lastKeyFrameAt > 0 ? now - lastKeyFrameAt : 0;
                            log("KEYFRAME #" + keyFrameCount + " at frame " + frameCount + " size=" + size + " sinceLast=" + sinceLast + "ms");
                            lastKeyFrameAt = now;
                        }

                        byte[] frameData = new byte[size];
                        buffer.get(frameData, 0, size);

                        // Non-blocking enqueue — never blocks the encoder thread
                        enqueueWrite(msgType, frameData);

                        frameCount++;
                        noOutputCount = 0;
                        // Log every frame for the first 10, then every 30th, and any frame after a >1s gap
                        if (frameCount <= 10 || frameCount % 30 == 0 || gap > 1000) {
                            log("Sent " + frameCount + " frames (gap=" + gap + "ms size=" + size + " flags=0x" + Integer.toHexString(info.flags) + " keys=" + keyFrameCount + ")");
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false);
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("Encoder output format changed: " + encoder.getOutputFormat());
                } else {
                    // outputIndex == INFO_TRY_AGAIN_LATER (-1) or other negative
                    noOutputCount++;
                    if (noOutputCount == 1 || noOutputCount == 30) {
                        log("dequeueOutputBuffer returned " + outputIndex + " (poll #" + noOutputCount + " sent=" + frameCount + ")");
                    }
                    if (noOutputCount == 30) {
                        log("WARNING: encoder no output for " + noOutputCount + " polls (~3s), sent " + frameCount + " total");
                    } else if (noOutputCount % 100 == 0) {
                        long gap = System.currentTimeMillis() - lastFrameTime;
                        log("WARNING: encoder stalled " + gap + "ms (" + noOutputCount + " polls, sent " + frameCount + " keys=" + keyFrameCount + ")");
                    }
                }
            } catch (Exception e) {
                err("Video output error: " + e.getMessage());
                running = false;
                break;
            }
        }
    }

    private void readCommands(SocketChannel ch) {
        // NIO Selector-based command reader — non-blocking
        ByteBuffer readBuf = ByteBuffer.allocate(4096);
        readBuf.flip(); // start empty, ready for reading

        try (Selector selector = Selector.open()) {
            ch.register(selector, SelectionKey.OP_READ);
            log("Command reader started (NIO Selector)");

            while (running) {
                // Wait for data, up to frameIntervalMs
                selector.select(frameIntervalMs);
                selector.selectedKeys().clear();

                // Read available data into buffer
                readBuf.compact();
                int n = ch.read(readBuf);
                readBuf.flip();
                if (n == -1) {
                    log("Command reader: EOF");
                    running = false;
                    break;
                }

                // Process complete commands from buffer
                long cmdCount = 0;
                parseLoop:
                while (readBuf.remaining() > 0) {
                    readBuf.mark();
                    try {
                        int cmd = readBuf.get() & 0xFF;
                        switch (cmd) {
                            case CMD_LAUNCH_APP: {
                                if (readBuf.remaining() < 4) { readBuf.reset(); break parseLoop; }
                                int len = readBuf.getInt();
                                if (readBuf.remaining() < len) { readBuf.reset(); break parseLoop; }
                                byte[] buf = new byte[len];
                                readBuf.get(buf);
                                launchApp(new String(buf));
                                break;
                            }
                            case CMD_GO_BACK:
                                execFast("input -d " + displayId + " keyevent 4");
                                checkStackEmpty();
                                break;
                            case CMD_GO_HOME:
                                log("Home: no-op (car handles launcher navigation)");
                                break;
                            case CMD_INPUT_TAP: {
                                if (readBuf.remaining() < 8) { readBuf.reset(); break parseLoop; }
                                int x = readBuf.getInt();
                                int y = readBuf.getInt();
                                execFast("input -d " + displayId + " tap " + x + " " + y);
                                break;
                            }
                            case CMD_INPUT_SWIPE: {
                                if (readBuf.remaining() < 20) { readBuf.reset(); break parseLoop; }
                                int x1 = readBuf.getInt();
                                int y1 = readBuf.getInt();
                                int x2 = readBuf.getInt();
                                int y2 = readBuf.getInt();
                                int dur = readBuf.getInt();
                                execFast("input -d " + displayId + " swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + dur);
                                break;
                            }
                            case CMD_INPUT_TOUCH: {
                                if (readBuf.remaining() < 17) { readBuf.reset(); break parseLoop; }
                                int action = readBuf.get() & 0xFF;
                                int pointerId = readBuf.getInt();
                                int tx = readBuf.getInt();
                                int ty = readBuf.getInt();
                                float pressure = readBuf.getFloat();
                                injectTouch(action, pointerId, tx, ty, pressure);
                                cmdCount++;
                                if (cmdCount <= 3 || cmdCount % 100 == 0) {
                                    log("Touch cmd #" + cmdCount + " action=" + action + " ptr=" + pointerId + " x=" + tx + " y=" + ty);
                                }
                                break;
                            }
                            case CMD_STOP:
                                running = false;
                                break;
                            default:
                                err("Unknown command: 0x" + Integer.toHexString(cmd));
                        }
                    } catch (java.nio.BufferUnderflowException e) {
                        readBuf.reset(); // incomplete command, wait for more data
                        break;
                    }
                }
            }
        } catch (Exception e) {
            err("Command error: " + e.getMessage());
            running = false;
        }
    }

    private void launchApp(String packageName) {
        String component = resolveActivity(packageName);
        String cmd;
        if (component != null) {
            cmd = "am start --display " + displayId + " -n " + component;
        } else {
            cmd = "am start --display " + displayId
                    + " -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER"
                    + " " + packageName;
        }
        log("Launching: " + cmd);
        execShell(cmd);
    }

    private String resolveActivity(String packageName) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + packageName});
            byte[] buf = new byte[1024];
            int len = p.getInputStream().read(buf);
            if (len > 0) {
                String output = new String(buf, 0, len);
                String[] lines = output.trim().split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (lines[i].contains("/")) {
                        return lines[i].trim();
                    }
                }
            }
        } catch (Exception e) {
            err("resolveActivity failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Inject a MotionEvent directly via InputManager — bypasses shell entirely.
     * Supports multi-touch: tracks active pointers and builds MotionEvent with all of them.
     * @param action 0=DOWN, 1=MOVE, 2=UP
     */
    private void injectTouch(int action, int pointerId, int x, int y, float pressure) {
        // Update pointer state
        if (action == 0) { // DOWN
            activePointers.put(pointerId, new float[]{x, y, pressure});
            if (activePointers.size() == 1) {
                touchDownTime = android.os.SystemClock.uptimeMillis();
            }
        } else if (action == 1) { // MOVE
            float[] coords = activePointers.get(pointerId);
            if (coords != null) {
                coords[0] = x;
                coords[1] = y;
                coords[2] = pressure;
            } else {
                return; // MOVE without DOWN — ignore
            }
        } else if (action == 2) { // UP
            // Don't remove yet — need pointer in the event
        }

        if (inputManager != null && injectInputEventMethod != null) {
            try {
                int pointerCount = activePointers.size();
                if (pointerCount == 0) return;

                // Use pre-allocated pools — no per-touch allocation
                int i = 0;
                int actionIndex = 0;
                for (java.util.Map.Entry<Integer, float[]> entry : activePointers.entrySet()) {
                    if (entry.getKey() == pointerId) actionIndex = i;

                    propsPool[i].id = entry.getKey();
                    propsPool[i].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;

                    coordsPool[i].x = entry.getValue()[0];
                    coordsPool[i].y = entry.getValue()[1];
                    coordsPool[i].pressure = entry.getValue()[2];
                    coordsPool[i].size = 1.0f;
                    i++;
                }

                // Compute the actual MotionEvent action
                int motionAction;
                if (action == 0) { // DOWN
                    if (pointerCount == 1) {
                        motionAction = android.view.MotionEvent.ACTION_DOWN;
                    } else {
                        motionAction = android.view.MotionEvent.ACTION_POINTER_DOWN
                                | (actionIndex << android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    }
                } else if (action == 2) { // UP
                    if (pointerCount == 1) {
                        motionAction = android.view.MotionEvent.ACTION_UP;
                    } else {
                        motionAction = android.view.MotionEvent.ACTION_POINTER_UP
                                | (actionIndex << android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    }
                } else { // MOVE
                    motionAction = android.view.MotionEvent.ACTION_MOVE;
                }

                long now = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent event = android.view.MotionEvent.obtain(
                        touchDownTime, now, motionAction,
                        pointerCount, propsPool, coordsPool,
                        0, 0, 1.0f, 1.0f,
                        0, 0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);

                // Target our virtual display
                if (setDisplayIdMethod != null) {
                    setDisplayIdMethod.invoke(event, displayId);
                }

                // INJECT_INPUT_EVENT_MODE_ASYNC = 0
                injectInputEventMethod.invoke(inputManager, event, 0);
                event.recycle();

                // Touch injection wakes the physical display — re-power-off (throttled).
                // Run on background thread to avoid jitter on the touch injection path.
                long nowMs = System.currentTimeMillis();
                if (running && nowMs - lastPowerOffTime > 1000) {
                    lastPowerOffTime = nowMs;
                    new Thread(() -> setPhysicalDisplayPower(false), "PowerOff").start();
                }
            } catch (Exception e) {
                err("MotionEvent injection failed: " + e.getMessage());
                // Fallback to shell for this event
                if (action == 0 || action == 2) {
                    execFast("input -d " + displayId + " tap " + x + " " + y);
                }
            }
        } else {
            // Fallback: shell commands (no multi-touch, limited gestures)
            if (action == 0) { // DOWN — record start position
                activePointers.put(pointerId, new float[]{x, y, pressure});
            } else if (action == 2) { // UP — execute tap or swipe
                float[] start = activePointers.get(pointerId);
                if (start != null) {
                    float dx = x - start[0];
                    float dy = y - start[1];
                    if (Math.sqrt(dx * dx + dy * dy) < 20) {
                        execFast("input -d " + displayId + " tap " + x + " " + y);
                    } else {
                        execFast("input -d " + displayId + " swipe " +
                                (int) start[0] + " " + (int) start[1] + " " + x + " " + y + " 200");
                    }
                }
            }
        }

        // Remove pointer after UP
        if (action == 2) {
            activePointers.remove(pointerId);
            // When no pointers remain, force-clear everything (prevents ghost fingers)
            if (activePointers.isEmpty()) {
                activePointers.clear();
                touchDownTime = 0;
            }
        }
    }

    /** Fast input injection via persistent shell — no fork+exec overhead */
    private void execFast(String command) {
        try {
            if (shellInput != null) {
                shellInput.write((command + "\n").getBytes());
                shellInput.flush();
                return;
            }
        } catch (Exception ignored) {}
        // Fallback to execShell if persistent shell died
        execShell(command);
    }

    private String execShellOutput(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            byte[] buf = new byte[64];
            int len = p.getInputStream().read(buf);
            p.waitFor();
            return (len > 0) ? new String(buf, 0, len).trim() : null;
        } catch (Exception e) { return null; }
    }

    private void execShell(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            p.waitFor();
            if (p.exitValue() != 0) {
                byte[] errBuf = new byte[512];
                int len = p.getErrorStream().read(errBuf);
                if (len > 0) {
                    err("Shell failed (" + p.exitValue() + "): " + new String(errBuf, 0, len).trim());
                }
            }
        } catch (Exception e) {
            err("execShell error: " + e.getMessage());
        }
    }

    /**
     * Turn the physical display on or off via SurfaceControl.setDisplayPowerMode().
     * This is the scrcpy approach — it controls the display backlight without
     * affecting system sleep, encoder surfaces, or Wireless Debugging.
     */
    private static Class<?> displayControlClass;
    private static boolean displayControlLoaded = false;

    /**
     * Load DisplayControl from services.jar (Android 14+).
     * On older Android, DisplayControl is on the classpath directly.
     * On Android 14+, it must be loaded via ClassLoaderFactory from services.jar.
     */
    private static Class<?> getDisplayControlClass() {
        if (displayControlLoaded) return displayControlClass;
        displayControlLoaded = true;

        // Try direct class load first (Android < 14)
        try {
            displayControlClass = Class.forName("com.android.server.display.DisplayControl");
            return displayControlClass;
        } catch (ClassNotFoundException ignored) {}

        // Android 14+: load from services.jar via ClassLoaderFactory
        try {
            Class<?> clFactory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method createCL = clFactory.getDeclaredMethod("createClassLoader",
                    String.class, String.class, String.class, ClassLoader.class,
                    int.class, boolean.class, String.class);
            ClassLoader cl = (ClassLoader) createCL.invoke(null,
                    "/system/framework/services.jar", null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);
            displayControlClass = cl.loadClass("com.android.server.display.DisplayControl");

            // Load the native library required by DisplayControl
            Method loadLib = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            loadLib.setAccessible(true);
            loadLib.invoke(Runtime.getRuntime(), displayControlClass, "android_servers");

            log("DisplayControl loaded from services.jar");
            return displayControlClass;
        } catch (Exception e) {
            err("Failed to load DisplayControl from services.jar: " + e.getMessage());
            return null;
        }
    }

    private void setPhysicalDisplayPower(boolean on) {
        int mode = on ? 2 : 0; // POWER_MODE_NORMAL=2, POWER_MODE_OFF=0
        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");

            // Try DisplayControl (Android 14+: loaded from services.jar)
            long[] displayIds = null;
            Class<?> dcClass = getDisplayControlClass();
            if (dcClass != null) {
                try {
                    displayIds = (long[]) dcClass.getMethod("getPhysicalDisplayIds").invoke(null);
                } catch (Exception e) {
                    err("DisplayControl.getPhysicalDisplayIds failed: " + e.getMessage());
                }
            }

            // Fallback to SurfaceControl.getPhysicalDisplayIds() (Android < 14)
            if (displayIds == null) {
                try {
                    displayIds = (long[]) scClass.getMethod("getPhysicalDisplayIds").invoke(null);
                } catch (Exception e) {
                    err("SurfaceControl.getPhysicalDisplayIds failed: " + e.getMessage());
                }
            }

            if (displayIds != null && displayIds.length > 0) {
                Method setMode = scClass.getMethod("setDisplayPowerMode", android.os.IBinder.class, int.class);
                for (long id : displayIds) {
                    android.os.IBinder token = null;
                    if (dcClass != null) {
                        try {
                            token = (android.os.IBinder) dcClass.getMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
                        } catch (Exception ignored) {}
                    }
                    if (token == null) {
                        try {
                            token = (android.os.IBinder) scClass.getMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
                        } catch (Exception ignored) {}
                    }
                    if (token != null) {
                        setMode.invoke(null, token, mode);
                    }
                }
                log("Physical display power via SurfaceControl: " + (on ? "ON" : "OFF"));
                return;
            } else {
                err("No physical display IDs found via DisplayControl or SurfaceControl");
            }
        } catch (Exception e) {
            err("setPhysicalDisplayPower reflection failed: " + e.getMessage());
        }

        // Fallback: shell command
        if (on) {
            execShell("cmd display power-on 0");
        } else {
            execShell("cmd display power-off 0");
        }
        log("Physical display power via shell fallback: " + (on ? "ON" : "OFF"));
    }

    /**
     * Set the IME display policy via IWindowManager reflection.
     * This tells Android to show the keyboard on our VD instead of the default display.
     */
    private void setDisplayImePolicy(int displayId) {
        try {
            // Get IWindowManager via ServiceManager
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
            Object windowBinder = getService.invoke(null, "window");

            Class<?> stubClass = Class.forName("android.view.IWindowManager$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder.class);
            Object windowManager = asInterface.invoke(null, windowBinder);

            // Call setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_LOCAL=0)
            Method setImePolicy = windowManager.getClass().getDeclaredMethod(
                    "setDisplayImePolicy", int.class, int.class);
            setImePolicy.setAccessible(true);
            setImePolicy.invoke(windowManager, displayId, 0);
            log("IME policy set to LOCAL for display " + displayId);
        } catch (Exception e) {
            err("setDisplayImePolicy failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * After a back press, check if the display still has any activities.
     * If empty, send MSG_STACK_EMPTY to the client.
     * Runs on a background thread to avoid blocking the command reader for 500ms+.
     */
    private void checkStackEmpty() {
        new Thread(this::checkStackEmptyImpl, "StackCheck").start();
    }

    private void checkStackEmptyImpl() {
        try {
            // Small delay to let the back press take effect
            Thread.sleep(300);
            // Count non-home tasks on our display.
            // Uses grep -c for robustness across Android versions — avoids sed section parsing.
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "dumpsys activity activities 2>/dev/null" +
                    " | grep -E 'display(Id)?=#?" + displayId + "'" +
                    " | grep -c 'Task{'"});
            byte[] buf = new byte[64];
            int len = p.getInputStream().read(buf);
            p.waitFor();
            String result = (len > 0) ? new String(buf, 0, len).trim() : "0";
            int taskCount = 0;
            try { taskCount = Integer.parseInt(result); } catch (NumberFormatException ignored) {}
            log("Display " + displayId + " has " + taskCount + " task(s)");
            if (taskCount == 0) {
                log("Display " + displayId + " stack is empty");
                enqueueWriteByte(MSG_STACK_EMPTY);
            }
        } catch (Exception e) {
            err("checkStackEmpty failed: " + e.getMessage());
        }
    }

    private void cleanup() {
        running = false;
        // Close persistent shell
        if (persistentShell != null) {
            try { shellInput.close(); } catch (Exception ignored) {}
            persistentShell.destroy();
            persistentShell = null;
            shellInput = null;
        }
        // Restore screen timeout
        if (savedScreenOffTimeout != null) {
            try {
                execShell("settings put system screen_off_timeout " + savedScreenOffTimeout);
                log("Screen timeout restored to " + savedScreenOffTimeout + "ms");
            } catch (Exception ignored) {}
        }
        // Restore lift/proximity wake
        if (savedLiftWakeup != null) {
            try {
                execShell("settings put system lift_wakeup_enabled " + savedLiftWakeup);
                execShell("settings put system proximity_wakeup_enabled " + savedProximityWakeup);
                log("Lift/proximity wake restored");
            } catch (Exception ignored) {}
        }
        // Restore physical display
        setPhysicalDisplayPower(true);
        execShell("input keyevent 224"); // KEYCODE_WAKEUP — ensures screen fully wakes
        log("Physical display restored");
        if (scaler != null) {
            scaler.stop();
            scaler = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception ignored) {}
            encoder = null;
        }
        log("Cleanup complete");
    }

    private static void log(String msg) {
        System.out.println("[VDServer] " + msg);
        System.out.flush();
    }

    private static void err(String msg) {
        System.err.println("[VDServer] " + msg);
        System.err.flush();
    }
}
