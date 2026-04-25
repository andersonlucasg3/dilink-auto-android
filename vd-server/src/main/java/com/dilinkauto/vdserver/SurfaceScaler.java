package com.dilinkauto.vdserver;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * GPU-accelerated surface scaler.
 * Renders a SurfaceTexture (VD content at full resolution) onto an output Surface
 * (encoder input at car viewport resolution) using OpenGL ES.
 *
 * All EGL/GL operations happen on the scaler thread (EGL contexts are thread-local).
 * Call start() to launch the thread, then getInputSurface() (blocks until ready).
 */
public class SurfaceScaler {

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    private final Surface encoderSurface;
    private final int inputWidth, inputHeight;
    private final int outputWidth, outputHeight;

    private volatile Surface inputSurface; // VD renders here
    private volatile boolean running = true;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private Thread thread;
    private android.os.HandlerThread callbackThread;

    private final long frameIntervalMs;

    public SurfaceScaler(Surface encoderSurface, int inputWidth, int inputHeight,
                         int outputWidth, int outputHeight, long frameIntervalMs) {
        this.encoderSurface = encoderSurface;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.frameIntervalMs = frameIntervalMs;
    }

    /** Start the scaler thread. Call getInputSurface() after this. */
    public void start() {
        thread = new Thread(this::run, "SurfaceScaler");
        thread.setDaemon(true);
        thread.start();
    }

    /** The Surface that the VirtualDisplay should render into. Blocks until ready. */
    public Surface getInputSurface() {
        try { readyLatch.await(); } catch (InterruptedException ignored) {}
        return inputSurface;
    }

    private void run() {
        // All EGL/GL init on THIS thread (EGL contexts are thread-local)
        EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0);

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        EGLContext eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0],
                encoderSurface, surfaceAttribs, 0);

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        // GL program
        int program = createProgram();
        GLES20.glUseProgram(program);

        // External texture for SurfaceTexture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int texId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        // SurfaceTexture — the VD renders here
        SurfaceTexture surfaceTexture = new SurfaceTexture(texId);
        surfaceTexture.setDefaultBufferSize(inputWidth, inputHeight);
        inputSurface = new Surface(surfaceTexture);

        // Signal that the input surface is ready
        readyLatch.countDown();

        // Frame sync — no Handler/Looper needed, just poll with a short sleep
        final Object frameLock = new Object();
        final boolean[] frameAvailable = {false};
        // Use a dedicated looper thread for the callback
        android.os.HandlerThread ht = new android.os.HandlerThread("ScalerCB");
        ht.start();
        callbackThread = ht;
        surfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameLock) {
                frameAvailable[0] = true;
                frameLock.notifyAll();
            }
        }, new android.os.Handler(ht.getLooper()));

        // Fullscreen quad — Y texcoords flipped (SurfaceTexture is top-down, GL is bottom-up)
        float[] vertices = {
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f
        };
        FloatBuffer vertexBuf = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuf.put(vertices).position(0);

        int aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");

        GLES20.glViewport(0, 0, outputWidth, outputHeight);

        // Render loop — draws at least once per frameIntervalMs even on static content.
        // This feeds the encoder's input surface so it can produce output continuously.
        // updateTexImage() is only called when a new frame is available (otherwise it throws).
        long swapCount = 0;
        long newFrameCount = 0;
        long idleSwapCount = 0;
        System.out.println("[SurfaceScaler] Render loop starting, frameIntervalMs=" + frameIntervalMs);

        while (running) {
            boolean hasNewFrame;
            synchronized (frameLock) {
                if (!frameAvailable[0] && running) {
                    try { frameLock.wait(frameIntervalMs); } catch (InterruptedException ignored) {}
                }
                hasNewFrame = frameAvailable[0];
                frameAvailable[0] = false;
            }
            if (!running) break;

            if (hasNewFrame) {
                surfaceTexture.updateTexImage();
                newFrameCount++;
            } else {
                idleSwapCount++;
            }
            swapCount++;
            if (swapCount <= 3 || swapCount % 30 == 0) {
                System.out.println("[SurfaceScaler] swap #" + swapCount + " newFrames=" + newFrameCount + " idleSwaps=" + idleSwapCount + " hasNew=" + hasNewFrame);
            }

            // Always re-draw and swap — pushes the current texture to the encoder
            // even when the VD content is static. This ensures the encoder always
            // has input and can produce output frames continuously.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);

            vertexBuf.position(0);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertexBuf);
            GLES20.glEnableVertexAttribArray(aPosition);

            vertexBuf.position(2);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuf);
            GLES20.glEnableVertexAttribArray(aTexCoord);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        }

        // Cleanup
        inputSurface.release();
        surfaceTexture.release();
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglTerminate(eglDisplay);
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException ignored) {}
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
            callbackThread = null;
        }
    }

    private static int createProgram() {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        return prog;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
