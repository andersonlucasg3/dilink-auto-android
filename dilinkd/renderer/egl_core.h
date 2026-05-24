#pragma once
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/native_window.h>

namespace dilink {

// RAII EGL context management. Single-threaded use (pipeline thread only).
class EglCore {
public:
    EglCore();
    ~EglCore();

    EglCore(const EglCore&) = delete;
    EglCore& operator=(const EglCore&) = delete;

    // Initialize EGL display + context + window surface bound to encoder input surface.
    // encode_w, encode_h: encoder output resolution (car viewport).
    bool init(ANativeWindow* encoder_surface, int encode_w, int encode_h);

    // Create a GL texture bound to SurfaceTexture for VirtualDisplay input.
    // Returns texture ID. The caller creates an ASurfaceTexture from this texture.
    // On success, vd_surface is set to the ANativeWindow wrapping the SurfaceTexture.
    bool create_input_texture(int display_w, int display_h,
                              GLuint& out_tex_id, ANativeWindow*& out_vd_surface);

    // Make this context current on the calling thread.
    bool make_current();

    // Swap buffers (submit rendered frame to encoder).
    bool swap_buffers();

    // Viewport + clear for a new frame.
    void begin_frame();

    // GL program handle for the passthrough shader.
    GLuint program() const { return program_; }
    GLint  pos_loc() const { return pos_loc_; }
    GLint  tex_loc() const { return tex_loc_; }
    GLuint input_tex() const { return input_tex_id_; }

    // Cleanup
    void destroy();

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLConfig config_ = nullptr;

    GLuint program_ = 0;
    GLint  pos_loc_ = -1;
    GLint  tex_loc_ = -1;
    GLuint input_tex_id_ = 0;
    int encode_w_ = 0;
    int encode_h_ = 0;
    bool initialized_ = false;
};

} // namespace dilink
