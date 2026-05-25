#pragma once
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/native_window.h>

namespace dilink {

class EglCore {
public:
    EglCore();
    ~EglCore();

    EglCore(const EglCore&) = delete;
    EglCore& operator=(const EglCore&) = delete;

    bool init(ANativeWindow* encoder_surface, int encode_w, int encode_h);

    // Create GL texture for VD input. SurfaceTexture is created in Java.
    bool create_input_texture(int display_w, int display_h,
                              GLuint& out_tex_id, ANativeWindow*& out_vd_surface);

    void set_surface_texture(void* surface_texture);

    // Update GL texture from SurfaceTexture (called from Java or pipeline)
    void update_tex_image();

    bool make_current();
    bool swap_buffers();
    void begin_frame();

    GLuint program() const { return program_; }
    GLint  pos_loc() const { return pos_loc_; }
    GLint  tex_loc() const { return tex_loc_; }
    GLuint input_tex() const { return input_tex_id_; }
    void* surface_texture() const { return surface_texture_; }
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
    int encode_w_ = 0, encode_h_ = 0;
    bool initialized_ = false;

    void* surface_texture_ = nullptr;
    ANativeWindow* vd_surface_ = nullptr;
};

} // namespace dilink


