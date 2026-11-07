package es.dasemu.wanderer.render

import android.opengl.GLES30.*

object GlUtil {
    fun program(vertexSource: String, fragmentSource: String): Int {
        val vs = shader(GL_VERTEX_SHADER, vertexSource)
        val fs = shader(GL_FRAGMENT_SHADER, fragmentSource)
        val p = glCreateProgram()
        glAttachShader(p, vs)
        glAttachShader(p, fs)
        glLinkProgram(p)
        glDeleteShader(vs)
        glDeleteShader(fs)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetProgramInfoLog(p)
            glDeleteProgram(p)
            throw IllegalStateException("Program link failed: $log")
        }
        return p
    }

    private fun shader(type: Int, source: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, source)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            glDeleteShader(s)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return s
    }

    fun uniforms(program: Int, vararg names: String): Map<String, Int> =
        names.associateWith { glGetUniformLocation(program, it) }

    fun genTexture(filter: Int, wrap: Int): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap)
        return ids[0]
    }
}
