package com.dilinkauto.vdserver

import org.junit.Assert.assertTrue
import org.junit.Test

class GlPipelineShaderTest {

    @Test
    fun testVertexShaderDeclaresAndAppliesTexMatrix() {
        val vs = GlPipeline.VERTEX_SHADER_SRC
        assertTrue("Vertex shader must declare uniform mat4 uTexMatrix", vs.contains("uniform mat4 uTexMatrix;"))
        assertTrue("Vertex shader must transform aTexCoord by uTexMatrix", vs.contains("uTexMatrix * aTexCoord") || vs.contains("uTexMatrix*aTexCoord"))
    }

    @Test
    fun testFragmentShaderDeclaresSamplerExternalOes() {
        val fs = GlPipeline.FRAGMENT_SHADER_SRC
        assertTrue("Fragment shader must require GL_OES_EGL_image_external", fs.contains("GL_OES_EGL_image_external"))
        assertTrue("Fragment shader must declare uniform samplerExternalOES", fs.contains("uniform samplerExternalOES"))
    }
}
