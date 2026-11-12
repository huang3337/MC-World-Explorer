package com.mcworldexplorer.map;

import java.awt.image.BufferedImage;
import java.util.BitSet;
import java.util.LinkedHashSet;

/** Bounded display coverage for unreadable terrain or its shading dependencies. */
public final class MapReadIssues {
    private final BitSet pixels = new BitSet(256 * 256);
    private final LinkedHashSet<String> reasons = new LinkedHashSet<>();

    void add(MapTileKey key, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, String reason) {
        var bounds = key.bounds();
        int scale = key.zoom().blocksPerPixel();
        // A failed north/west neighbour affects only the corresponding shading edge.
        int x0 = (int) Math.max(0, Math.min(255, Math.floorDiv((long) minChunkX * 16 - bounds.minX(), scale)));
        int z0 = (int) Math.max(0, Math.min(255, Math.floorDiv((long) minChunkZ * 16 - bounds.minZ(), scale)));
        int x1 = (int) Math.max(0, Math.min(255, Math.floorDiv(((long) maxChunkX + 1) * 16 - 1 - bounds.minX(), scale)));
        int z1 = (int) Math.max(0, Math.min(255, Math.floorDiv(((long) maxChunkZ + 1) * 16 - 1 - bounds.minZ(), scale)));
        for (int z = z0; z <= z1; z++) pixels.set(z * 256 + x0, z * 256 + x1 + 1);
        if (reasons.size() < 8) reasons.add(reason.substring(0, Math.min(256, reason.length())));
    }

    public boolean isEmpty() { return pixels.isEmpty(); }
    public boolean containsPixel(int x, int z) { return pixels.get(z * 256 + x); }
    public String description() {
        return "局部不可读：红色斜线为不可读区域或受影响的着色边界；其余区域可浏览。\n"
                + String.join("\n", reasons) + "\n未保存为完整地图，完整导出不可用；修复来源后刷新或点击重试。";
    }
    void mark(BufferedImage image) {
        for (int p = pixels.nextSetBit(0); p >= 0; p = pixels.nextSetBit(p + 1)) {
            int x = p % 256, z = p / 256;
            image.setRGB(x, z, ((x + z) % 10 < 3) ? 0xffdf5961 : 0xff49363c);
        }
    }
}
