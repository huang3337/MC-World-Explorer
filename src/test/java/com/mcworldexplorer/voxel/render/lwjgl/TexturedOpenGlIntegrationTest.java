package com.mcworldexplorer.voxel.render.lwjgl;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import com.mcworldexplorer.voxel.resource.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

class TexturedOpenGlIntegrationTest {
    @Test void uploadsDrawsReplacesAndReleasesRealAtlasInNativeContext() throws Exception {
        assumeTrue("1".equals(System.getenv("MCWORLD_GL_TEXTURE_TEST")),"原生 GL 仅显式启用");
        String path=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(path!=null);
        try(var archive=ClientResourceArchive.open(Path.of(path),"1.21.1")) {
            var textures=BlockTextureSet.load(archive,CancellationSignal.NONE);
            var scene=new VoxelSceneBuilder(textures).build(TexturedSceneIntegrationTest.sampleArea(),CancellationSignal.NONE).snapshot().orElseThrow();
            assertTrue(glfwInit(),"GLFW initialization failed");
            long window=0;
            try {
                glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
                glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
                window=glfwCreateWindow(960,720,"V0.7.1 texture validation",0,0);assertNotEquals(0,window);
                glfwMakeContextCurrent(window);GL.createCapabilities();
                int[] width={0},height={0};glfwGetFramebufferSize(window,width,height);
                glViewport(0,0,width[0],height[0]);glEnable(GL_DEPTH_TEST);glEnable(GL_CULL_FACE);
                var matrix=new Matrix4f().perspective((float)Math.toRadians(42),(float)width[0]/height[0],.1f,100)
                        .lookAt(18,18,24,5.5f,1.5f,4.5f,0,1,0);
                int previous=0;long start=System.nanoTime();
                try(var renderer=new LwjglSceneRenderer()) {
                    for(int i=0;i<10;i++) {
                        renderer.replace(scene);
                        glClearColor(.86f,.91f,.97f,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                        renderer.draw(matrix);glFinish();
                        int current=glGetInteger(GL_TEXTURE_BINDING_2D);
                        assertTrue(glIsTexture(current));
                        if(previous!=0)assertFalse(glIsTexture(previous),"替换后旧图集应释放");
                        previous=current;assertEquals(GL_NO_ERROR,glGetError());
                    }
                    String output=System.getenv("MCWE_UI_SNAPSHOT_DIR");
                    if(output!=null)save(Path.of(output).resolve("native-textured-blocks.png"),width[0],height[0]);
                    assertFalse(renderer.replace(scene,()->false));
                    assertTrue(glIsTexture(previous),"拒绝替换保留旧场景");
                }
                assertFalse(glIsTexture(previous),"关闭应释放最后图集");
                System.out.printf("Native texture validation: %s / %s; %dx%d; 10 upload/draw/replace cycles %.1f ms%n",
                        glGetString(GL_VENDOR),glGetString(GL_RENDERER),width[0],height[0],(System.nanoTime()-start)/1e6);
            } finally {
                GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);glfwTerminate();
            }
        }
    }
    private static void save(Path path,int w,int h)throws Exception {
        ByteBuffer pixels=MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(w,h),4));
        try {
            glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int at=(y*w+x)*4;
                int rgb=0xFF000000|((pixels.get(at)&255)<<16)|((pixels.get(at+1)&255)<<8)|(pixels.get(at+2)&255);
                image.setRGB(x,h-1-y,rgb);
            }
            Files.createDirectories(path.getParent());ImageIO.write(image,"png",path.toFile());
        }finally{MemoryUtil.memFree(pixels);}
    }
}
