#pragma once
#include <GLES2/gl2.h>
#include <android/native_window.h>

namespace dilink {

// Manages SurfaceTexture → GL texture → encoder surface passthrough rendering.
// Uses the EGL/GL context from EglCore. Single-threaded (pipeline thread only).
class TextureBlit {
public:
    // Initialize with EGL core's program locations and the VD input texture.
    // display_w, display_h: VirtualDisplay dimensions.
    void init(GLuint program, GLint pos_loc, GLint tex_loc,
              GLuint input_tex, int display_w, int display_h);

    // Set the ASurfaceTexture (created externally via NDK ANewSurfaceTextureFromGLTex).
    // This is the surface the VirtualDisplay renders into.
    void set_surface_texture(ANativeWindow* vd_surface);

    // Render the current VD frame to the encoder surface.
    // Call after updateTexImage() on the ASurfaceTexture.
    void render();

    // Fullscreen quad for passthrough (vertex + texcoord interleaved: x,y,u,v).
    static const float* quad_vertices();

private:
    GLuint program_ = 0;
    GLint  pos_loc_ = -1;
    GLint  tex_loc_ = -1;
    GLuint input_tex_ = 0;
    int    display_w_ = 0;
    int    display_h_ = 0;
};

} // namespace dilink
