#include "texture_blit.h"
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

namespace dilink {

// Fullscreen quad: x, y, u, v (4 vertices for triangle strip)
static const float QUAD[] = {
    -1.0f, -1.0f, 0.0f, 1.0f,   // bottom-left
     1.0f, -1.0f, 1.0f, 1.0f,   // bottom-right
    -1.0f,  1.0f, 0.0f, 0.0f,   // top-left
     1.0f,  1.0f, 1.0f, 0.0f    // top-right
};

const float* TextureBlit::quad_vertices() { return QUAD; }

void TextureBlit::init(GLuint program, GLint pos_loc, GLint tex_loc,
                        GLuint input_tex, int display_w, int display_h) {
    program_ = program;
    pos_loc_ = pos_loc;
    tex_loc_ = tex_loc;
    input_tex_ = input_tex;
    display_w_ = display_w;
    display_h_ = display_h;
}

void TextureBlit::set_surface_texture(ANativeWindow* vd_surface) {
    (void)vd_surface; // stored externally by pipeline
}

void TextureBlit::render() {
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, input_tex_);

    // Position attribute: stride 16 bytes (4 floats), offset 0
    glVertexAttribPointer(pos_loc_, 2, GL_FLOAT, GL_FALSE, 16, QUAD);
    glEnableVertexAttribArray(pos_loc_);

    // TexCoord attribute: stride 16 bytes, offset 8 bytes (skip 2 floats)
    glVertexAttribPointer(tex_loc_, 2, GL_FLOAT, GL_FALSE, 16, QUAD + 2);
    glEnableVertexAttribArray(tex_loc_);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(pos_loc_);
    glDisableVertexAttribArray(tex_loc_);
}

} // namespace dilink
