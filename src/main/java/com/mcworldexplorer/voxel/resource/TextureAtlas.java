package com.mcworldexplorer.voxel.resource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Nearest-filtered static PNG atlas, with a one-pixel extruded border per tile. */
public final class TextureAtlas {
    public static final int MAX_IMAGE_SIZE = 256;
    public static final int MAX_SIZE = 1024;
    public static final int MAX_TILES = 128;
    private final int width, height;
    private final int[] pixels;

    private TextureAtlas(int width, int height, int[] pixels) {
        this.width = width; this.height = height; this.pixels = pixels.clone();
    }
    public int width() { return width; }
    public int height() { return height; }
    public int[] pixels() { return pixels.clone(); }
    public long estimatedBytes() { return (long) pixels.length * Integer.BYTES; }
    public record Tile(int x, int y, int width, int height) { }

    public static BufferedImage decode(byte[] png) throws IOException {
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (png.length < 33 || !Arrays.equals(signature, Arrays.copyOf(png, 8)))
            throw new IOException("纹理不是有效 PNG");
        ImageReader reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            reader.setInput(input, true, true);
            int w = reader.getWidth(0), h = reader.getHeight(0);
            if (w < 1 || w != h || w > MAX_IMAGE_SIZE)
                throw new IOException("仅支持不超过 256×256 的正方形静态纹理");
            return reader.read(0);
        } finally { reader.dispose(); }
    }

    public static final class Builder {
        private final int[] pixels = new int[MAX_SIZE * MAX_SIZE];
        private final Map<String, Tile> tiles = new LinkedHashMap<>();
        private int x, y, rowHeight;

        public Tile add(String key, BufferedImage image) throws IOException {
            Tile existing = tiles.get(key);
            if (existing != null) return existing;
            int w = image.getWidth(), h = image.getHeight();
            if (w < 1 || h < 1 || w > MAX_IMAGE_SIZE || h > MAX_IMAGE_SIZE || tiles.size() >= MAX_TILES)
                throw new IOException("纹理图集单项尺寸或数量超限");
            if (x + w + 2 > MAX_SIZE) { x = 0; y += rowHeight; rowHeight = 0; }
            if (y + h + 2 > MAX_SIZE) throw new IOException("纹理图集超过 1024×1024 容量");
            Tile tile = new Tile(x + 1, y + 1, w, h);
            for (int py = -1; py <= h; py++) for (int px = -1; px <= w; px++)
                pixels[(y + py + 1) * MAX_SIZE + x + px + 1] = image.getRGB(
                        Math.max(0, Math.min(w - 1, px)), Math.max(0, Math.min(h - 1, py)));
            tiles.put(key, tile); x += w + 2; rowHeight = Math.max(rowHeight, h + 2);
            return tile;
        }
        public TextureAtlas build() {
            int height = 1;
            while (height < y + rowHeight) height *= 2;
            return new TextureAtlas(MAX_SIZE, height, Arrays.copyOf(pixels, MAX_SIZE * height));
        }
    }
}
