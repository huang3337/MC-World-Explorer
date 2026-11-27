package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.resource.TextureAtlas;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/** One bounded atlas per uploaded scene; only touched on the GL owner thread. */
final class OpenGlTextureResources implements AutoCloseable {
    private int texture;
    OpenGlTextureResources(TextureAtlas atlas) {
        if (atlas.width() > glGetInteger(GL_MAX_TEXTURE_SIZE) || atlas.height() > glGetInteger(GL_MAX_TEXTURE_SIZE))
            throw new IllegalStateException("显卡不支持当前纹理图集尺寸");
        ByteBuffer data = MemoryUtil.memAlloc(Math.toIntExact(atlas.estimatedBytes()));
        try {
            for (int argb : atlas.pixels()) data.put((byte)(argb >>> 16)).put((byte)(argb >>> 8)).put((byte)argb).put((byte)(argb >>> 24));
            data.flip(); texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, atlas.width(), atlas.height(), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new IllegalStateException("纹理图集上传失败：GL " + error);
        } catch (RuntimeException | Error e) { close(); throw e; }
        finally { MemoryUtil.memFree(data); }
    }
    void bind() { glBindTexture(GL_TEXTURE_2D, texture); }
    @Override public void close() { if (texture != 0) { glDeleteTextures(texture); texture = 0; } }
}
