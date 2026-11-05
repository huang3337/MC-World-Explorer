package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.render.lwjgl.VoxelShaderProgram;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.opengl.GL33.*;

/** Small navigation hint and crosshair; text is rasterized only when its content/scale changes. */
final class ViewerNavigationOverlay implements AutoCloseable {
    private final VoxelShaderProgram shader;
    private int vao, vbo, texture, imageWidth, imageHeight;
    private String previous = "";
    ViewerNavigationOverlay() throws IOException {
        shader = new VoxelShaderProgram();
        try {
            vao = glGenVertexArrays(); vbo = glGenBuffers(); texture = glGenTextures();
            glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, 6L * 5 * Float.BYTES, GL_DYNAMIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0L); glEnableVertexAttribArray(0);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, 5 * Float.BYTES, 3L * Float.BYTES); glEnableVertexAttribArray(2);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
        finally { glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0); }
    }
    void draw(int width, int height, float scale, String controls, String status, boolean captured) {
        scale = Math.clamp(scale, 1, 2);
        int margin = Math.round(10 * scale), panelWidth = Math.max(64, Math.min(2048, width - margin * 2));
        String identity = controls + "\n" + status + "|" + panelWidth + "|" + scale;
        if (!identity.equals(previous)) { upload(List.of(controls, status), panelWidth, scale, 24, true); previous = identity; }
        boolean depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        try {
            shader.use(); shader.setMvp(new Matrix4f().ortho2D(0, width, height, 0));
            shader.setOffset(0, 0); shader.setColor(0xFFFFFF, 1); shader.setShaded(false); shader.setTextured(true);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture); glBindVertexArray(vao);
            quad(margin, Math.max(margin, height - imageHeight - margin), imageWidth, imageHeight);
            if (captured) {
                shader.setTextured(false);
                float x = width / 2f, y = height / 2f, arm = 7 * scale, line = 1.5f * scale;
                shader.setColor(0x111111, 1); quad(x - arm - scale, y - line - scale, 2 * (arm + scale), 2 * (line + scale));
                quad(x - line - scale, y - arm - scale, 2 * (line + scale), 2 * (arm + scale));
                shader.setColor(0xFFFFFF, 1); quad(x - arm, y - line, arm * 2, line * 2); quad(x - line, y - arm, line * 2, arm * 2);
            }
        } finally {
            glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0);
            if (depth) glEnable(GL_DEPTH_TEST); if (cull) glEnable(GL_CULL_FACE);
        }
    }
    /** A separate instance owns the top-left target panel; it never reuploads unchanged text. */
    void drawTarget(int width, int height, float scale, List<String> lines) {
        scale = Math.clamp(scale,1,2);
        int margin = Math.round(10*scale), panelWidth = Math.max(64,Math.min(Math.round(600*scale),width-margin*2));
        int maxLines = Math.max(1,Math.min(5,(height/2-Math.round(20*scale))/Math.round(20*scale)));
        String identity = String.join("\n",lines)+"|"+panelWidth+"|"+scale+"|"+maxLines;
        if (!identity.equals(previous)) { upload(lines,panelWidth,scale,maxLines,false); previous=identity; }
        boolean depth=glIsEnabled(GL_DEPTH_TEST),cull=glIsEnabled(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);
        try {
            shader.use();shader.setMvp(new Matrix4f().ortho2D(0,width,height,0));shader.setOffset(0,0);
            shader.setColor(0xFFFFFF,1);shader.setShaded(false);shader.setTextured(true);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);glBindVertexArray(vao);
            quad(margin,margin,imageWidth,imageHeight);
        } finally { glBindVertexArray(0);glBindBuffer(GL_ARRAY_BUFFER,0);if(depth)glEnable(GL_DEPTH_TEST);if(cull)glEnable(GL_CULL_FACE); }
    }
    private void quad(float x, float y, float w, float h) {
        try (var stack = MemoryStack.stackPush()) {
            var vertices = stack.floats(x,y,0,0,0, x+w,y,0,1,0, x+w,y+h,0,1,1,
                    x,y,0,0,0, x+w,y+h,0,1,1, x,y+h,0,0,1);
            glBindBuffer(GL_ARRAY_BUFFER, vbo); glBufferSubData(GL_ARRAY_BUFFER, 0, vertices); glDrawArrays(GL_TRIANGLES, 0, 6);
        }
    }
    private static String fitText(FontMetrics metrics,String text,int width) {
        if(metrics.stringWidth(text)<=width)return text;
        int low=0,high=text.length();
        while(low<high){int middle=(low+high+1)/2;if(metrics.stringWidth(text.substring(0,middle)+"…")<=width)low=middle;else high=middle-1;}
        if(low>0&&Character.isHighSurrogate(text.charAt(low-1)))low--;
        return text.substring(0,low)+"…";
    }
    private void upload(List<String> paragraphs, int width, float scale, int maxLines, boolean wrap) {
        int padding = Math.round(10 * scale);
        Font font = new Font("Microsoft YaHei UI", Font.PLAIN, Math.round(14 * scale));
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = measure.createGraphics(); List<String> lines = new ArrayList<>(); int lineHeight;
        try {
            mg.setFont(font); var metrics = mg.getFontMetrics(); lineHeight = metrics.getHeight();
            for (String paragraph : paragraphs) {
                if (!wrap) { lines.add(fitText(metrics,paragraph,width-2*padding)); continue; }
                StringBuilder line = new StringBuilder();
                for (char c : paragraph.toCharArray()) {
                    if (!line.isEmpty() && metrics.stringWidth(line.toString() + c) > width - 2 * padding) {
                        int separator = line.lastIndexOf(" · ");
                        if (separator > 0) {
                            lines.add(line.substring(0, separator));
                            String remaining = line.substring(separator + 3); line.setLength(0); line.append(remaining);
                        } else { lines.add(line.toString()); line.setLength(0); }
                    }
                    line.append(c);
                }
                lines.add(line.toString());
            }
        } finally { mg.dispose(); }
        if (lines.size() > maxLines) { lines = new ArrayList<>(lines.subList(0,maxLines)); lines.set(maxLines-1, "…（完整诊断见主界面）"); }
        imageWidth = width; imageHeight = Math.min(512, padding * 2 + lineHeight * lines.size());
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(24, 31, 40, 232)); g.fillRoundRect(0, 0, imageWidth, imageHeight, padding, padding);
            g.setFont(font); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(244, 246, 248)); int y = padding + g.getFontMetrics().getAscent();
            for (String line : lines) { g.drawString(line, padding, y); y += lineHeight; }
        } finally { g.dispose(); }
        var pixels = MemoryUtil.memAlloc(imageWidth * imageHeight * 4);
        try {
            for (int y = 0; y < imageHeight; y++) for (int x = 0; x < imageWidth; x++) {
                int argb = image.getRGB(x, y); pixels.put((byte)(argb >> 16)).put((byte)(argb >> 8)).put((byte)argb).put((byte)(argb >> 24));
            }
            pixels.flip(); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, imageWidth, imageHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        } finally { MemoryUtil.memFree(pixels); }
    }
    @Override public void close() {
        if (texture != 0) glDeleteTextures(texture); if (vbo != 0) glDeleteBuffers(vbo); if (vao != 0) glDeleteVertexArrays(vao);
        texture = vbo = vao = 0; shader.close();
    }
}
