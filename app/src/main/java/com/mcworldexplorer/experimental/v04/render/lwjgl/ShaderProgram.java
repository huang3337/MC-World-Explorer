package com.mcworldexplorer.experimental.v04.render.lwjgl;

import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUniform3f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;

public final class ShaderProgram implements AutoCloseable {
    private int program;
    private final int mvpLocation;
    private final int colorLocation;

    public ShaderProgram(String vertexResource, String fragmentResource) throws IOException {
        int vertex = compile(GL_VERTEX_SHADER, read(vertexResource));
        try {
            int fragment = compile(GL_FRAGMENT_SHADER, read(fragmentResource));
            try {
                int created = glCreateProgram();
                try {
                    glAttachShader(created, vertex);
                    glAttachShader(created, fragment);
                    glLinkProgram(created);
                    if (glGetProgrami(created, GL_LINK_STATUS) == 0) {
                        throw new IllegalStateException(
                                "shader link failed: " + glGetProgramInfoLog(created));
                    }
                    program = created;
                } catch (RuntimeException e) {
                    glDeleteProgram(created);
                    throw e;
                }
            } finally {
                glDeleteShader(fragment);
            }
        } finally {
            glDeleteShader(vertex);
        }
        try {
            mvpLocation = requiredUniform("uMvp");
            colorLocation = requiredUniform("uColor");
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public void use() {
        glUseProgram(program);
    }

    public void setMvp(org.joml.Matrix4f matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            glUniformMatrix4fv(mvpLocation, false, buffer);
        }
    }

    public void setColor(int rgb) {
        glUniform3f(
                colorLocation,
                ((rgb >>> 16) & 0xFF) / 255.0f,
                ((rgb >>> 8) & 0xFF) / 255.0f,
                (rgb & 0xFF) / 255.0f);
    }

    private int requiredUniform(String name) {
        int location = glGetUniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException("shader uniform is missing: " + name);
        }
        return location;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return shader;
    }

    static String read(String resource) throws IOException {
        try (InputStream input = ShaderProgram.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("shader resource not found: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        if (program != 0) {
            glDeleteProgram(program);
            program = 0;
        }
    }
}
