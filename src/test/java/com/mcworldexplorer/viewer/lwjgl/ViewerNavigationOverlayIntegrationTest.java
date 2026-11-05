package com.mcworldexplorer.viewer.lwjgl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Native GL resource/pixel checks, not OS keyboard or user-flow acceptance. */
class ViewerNavigationOverlayIntegrationTest {
    @Test void targetPanelShowsChineseIdentityAndClearsOldTextAcrossFiveGenerations() throws Exception {
        assumeTrue("1".equals(System.getenv("MCWE_R2_NATIVE_TEST")), "原生 GL 需显式启用");
        assertTrue(glfwInit());long window=0;
        try {
            glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
            window=glfwCreateWindow(960,600,"R3 target GL validation",0,0);assertNotEquals(0,window);
            glfwMakeContextCurrent(window);GL.createCapabilities();glViewport(0,0,960,600);
            glEnable(GL_DEPTH_TEST);glEnable(GL_CULL_FACE);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
            for(int generation=0;generation<5;generation++) {
                int texture;
                try(var overlay=new ViewerNavigationOverlay()) {
                    float scale=generation%2==0?1.5f:1;
                    glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    overlay.drawTarget(960,600,scale,java.util.List.of("unknown_namespace:very_long_block_identity_with_original_name",
                            "方块归属：未确认（命名空间 unknown_namespace）","坐标：-4872, 180, -4904","显示状态：部分解析","说明：颜色未解析：未绑定处理器；保留原始纹理"));
                    texture=glGetInteger(GL_TEXTURE_BINDING_2D);glFinish();assertEquals(GL_NO_ERROR,glGetError());
                    var hit=readImage();long bright=0;
                    for(int y=10;y<200;y++)for(int x=10;x<900;x++)if((hit.getRGB(x,y)&0xffffff)>0xaaaaaa)bright++;
                    assertTrue(bright>500,"目标中文与原始 ID 必须产生可见像素");
                    String out=System.getProperty("mcwe.r3.output");if(out!=null&&generation<2){Files.createDirectories(Path.of(out));javax.imageio.ImageIO.write(hit,"png",Path.of(out,"target-"+scale+".png").toFile());}
                    glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    overlay.drawTarget(640,400,scale,java.util.List.of("无目标（查询距离 128 格）"));
                    assertEquals(0xff000000,readImage().getRGB(40,140),"目标消失后旧多行文字不能残留");
                    assertTrue(glIsEnabled(GL_DEPTH_TEST));assertTrue(glIsEnabled(GL_CULL_FACE));assertEquals(GL_NO_ERROR,glGetError());
                }
                assertFalse(glIsTexture(texture),"每次关闭必须释放目标纹理");
            }
        }finally{if(window!=0){GL.setCapabilities(null);glfwDestroyWindow(window);}glfwTerminate();}
    }
    @Test void rendersChineseHintsAndCrosshairAtMultipleSizesAndReleasesEveryGeneration() throws Exception {
        assumeTrue("1".equals(System.getenv("MCWE_R2_NATIVE_TEST")), "原生 GL 需显式启用");
        assertTrue(glfwInit()); long window = 0;
        try {
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(960, 600, "R2 GL validation", 0, 0); assertNotEquals(0, window);
            glfwMakeContextCurrent(window); GL.createCapabilities(); glViewport(0, 0, 960, 600);
            glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE); glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            for (int generation = 0; generation < 5; generation++) {
                int texture;
                try (var overlay = new ViewerNavigationOverlay()) {
                    float scale = generation % 2 == 0 ? 1.5f : 1;
                    glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    overlay.draw(960,600,scale,"自由视角 · WASD 移动 · Space / Shift 升降 · Ctrl 加速 · 滚轮调速 · Alt / Esc 释放 · R 复位 · O 环绕",
                            "接近已加载区块边界 · 不会自动加载新区块 · 速度 16 格/秒",true);
                    texture = glGetInteger(GL_TEXTURE_BINDING_2D); assertTrue(glIsTexture(texture));
                    glFinish(); assertEquals(GL_NO_ERROR,glGetError());
                    assertTrue(glIsEnabled(GL_DEPTH_TEST)); assertTrue(glIsEnabled(GL_CULL_FACE));
                    var image = readImage(); assertEquals(0xFFFFFFFF,image.getRGB(480,300),"捕获状态显示中心准星");
                    long bright=0;for(int y=480;y<600;y++)for(int x=0;x<960;x++)if((image.getRGB(x,y)&0xFFFFFF)>0xAAAAAA)bright++;
                    assertTrue(bright>300,"中文提示必须产生可见像素");
                    String out=System.getProperty("mcwe.r2.output");
                    if(out!=null&&generation<2){Files.createDirectories(Path.of(out));javax.imageio.ImageIO.write(image,"png",Path.of(out,"overlay-"+scale+".png").toFile());}
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    overlay.draw(640,400,scale,"鼠标已释放 · 点击画面进入自由视角 · O 环绕查看 · R 复位 · Esc 关闭","已超出已加载区块 · 区域外未加载",false);
                    assertEquals(0xFF000000,readImage().getRGB(480,300),"释放状态移除旧准星");
                    assertEquals(GL_NO_ERROR,glGetError());
                }
                assertFalse(glIsTexture(texture),"每代关闭释放提示纹理");
            }
        } finally {
            if(window!=0){GL.setCapabilities(null);glfwDestroyWindow(window);}glfwTerminate();
        }
    }
    private static BufferedImage readImage() {
        var pixels=MemoryUtil.memAlloc(960*600*4);
        try {
            glReadPixels(0,0,960,600,GL_RGBA,GL_UNSIGNED_BYTE,pixels);var image=new BufferedImage(960,600,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<600;y++)for(int x=0;x<960;x++){int p=(y*960+x)*4;image.setRGB(x,599-y,((pixels.get(p+3)&255)<<24)|((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255));}
            return image;
        } finally {MemoryUtil.memFree(pixels);}
    }
}
