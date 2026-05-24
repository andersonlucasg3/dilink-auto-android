#include "egl_core.h"
#include <android/log.h>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "dilinkd.EglCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {

static const char* VERTEX_SHADER =
    "attribute vec4 aPosition;"
    "attribute vec2 aTexCoord;"
    "varying vec2 vTexCoord;"
    "void main() {"
    "  gl_Position = aPosition;"
    "  vTexCoord = aTexCoord;"
    "}";

static const char* FRAGMENT_SHADER =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;"
    "varying vec2 vTexCoord;"
    "uniform samplerExternalOES sTexture;"
    "void main() {"
    "  gl_FragColor = texture2D(sTexture, vTexCoord);"
    "}";

static GLuint load_shader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint info_len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &info_len);
        if (info_len > 1) {
            char* buf = static_cast<char*>(malloc(info_len));
            glGetShaderInfoLog(shader, info_len, nullptr, buf);
            LOGE("Shader compile error: %s", buf);
            free(buf);
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static GLuint create_program() {
    GLuint vs = load_shader(GL_VERTEX_SHADER, VERTEX_SHADER);
    GLuint fs = load_shader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    if (!vs || !fs) return 0;

    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);

    GLint linked = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint info_len = 0;
        glGetProgramiv(prog, GL_INFO_LOG_LENGTH, &info_len);
        if (info_len > 1) {
            char* buf = static_cast<char*>(malloc(info_len));
            glGetProgramInfoLog(prog, info_len, nullptr, buf);
            LOGE("Program link error: %s", buf);
            free(buf);
        }
        glDeleteProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);
        return 0;
    }

    glDeleteShader(vs);
    glDeleteShader(fs);
    return prog;
}

EglCore::EglCore() = default;

EglCore::~EglCore() {
    destroy();
}

bool EglCore::init(ANativeWindow* encoder_surface, int encode_w, int encode_h) {
    encode_w_ = encode_w;
    encode_h_ = encode_h;

    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(display_, &major, &minor)) {
        LOGE("eglInitialize failed");
        return false;
    }

    const EGLint config_attribs[] = {
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_NONE
    };

    EGLint num_configs;
    if (!eglChooseConfig(display_, config_attribs, &config_, 1, &num_configs) || num_configs < 1) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    const EGLint context_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };

    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, context_attribs);
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }

    surface_ = eglCreateWindowSurface(display_, config_, encoder_surface, nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }

    if (!make_current()) return false;

    program_ = create_program();
    if (!program_) {
        LOGE("Failed to create GL program");
        return false;
    }

    glUseProgram(program_);
    pos_loc_ = glGetAttribLocation(program_, "aPosition");
    tex_loc_ = glGetAttribLocation(program_, "aTexCoord");
    glViewport(0, 0, encode_w, encode_h);

    LOGI("EGL ready: %dx%d", encode_w, encode_h);
    initialized_ = true;
    return true;
}

bool EglCore::create_input_texture(int display_w, int display_h,
                                    GLuint& out_tex_id, ANativeWindow*& out_vd_surface) {
    if (!initialized_) return false;

    GLuint tex_id;
    glGenTextures(1, &tex_id);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex_id);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    input_tex_id_ = tex_id;
    out_tex_id = tex_id;

    // Create ASurfaceTexture from the GL texture (caller does this via NDK API).
    // The SurfaceTexture provides the ANativeWindow (Surface) for the VirtualDisplay.
    // This is done in texture_blit.cpp.
    (void)display_w; (void)display_h;
    out_vd_surface = nullptr; // caller sets this
    return true;
}

bool EglCore::make_current() {
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

bool EglCore::swap_buffers() {
    if (!eglSwapBuffers(display_, surface_)) {
        LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

void EglCore::begin_frame() {
    glClear(GL_COLOR_BUFFER_BIT);
}

void EglCore::destroy() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

        if (surface_ != EGL_NO_SURFACE) {
            eglDestroySurface(display_, surface_);
            surface_ = EGL_NO_SURFACE;
        }
        if (context_ != EGL_NO_CONTEXT) {
            eglDestroyContext(display_, context_);
            context_ = EGL_NO_CONTEXT;
        }
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
    }

    if (program_) {
        glDeleteProgram(program_);
        program_ = 0;
    }
    if (input_tex_id_) {
        glDeleteTextures(1, &input_tex_id_);
        input_tex_id_ = 0;
    }

    initialized_ = false;
}

} // namespace dilink
