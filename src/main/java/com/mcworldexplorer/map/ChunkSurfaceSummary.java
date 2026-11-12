package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.BlockColorPalette;
import com.mcworldexplorer.preview.ChunkSurface;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/** 不含 NBT 的不可变区块摘要；缩放无关，原始方块身份不丢弃。 */
final class ChunkSurfaceSummary {
    private final int[] colors = new int[256];
    private final int[] heights = new int[256];
    private final short[] identities = new short[256];
    private final BitSet unknown = new BitSet(256);
    private final List<String> palette;
    private final List<MapMarker> markers;
    private final String markerDiagnostic;
    private final int populatedColumns;
    private final long estimatedBytes;
    private final long[][] levels = new long[4][];

    ChunkSurfaceSummary(ChunkSurface surface, List<MapMarker> markers, String markerDiagnostic) {
        Arrays.fill(identities, (short) -1);
        Map<String, Short> ids = new HashMap<>();
        List<String> names = new ArrayList<>();
        int populated = 0;
        long bytes = 8192; // 数组、BitSet、对象、缓存键与容器的保守基础估算。
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var column = surface.getColumn(x, z);
            if (column.isEmpty()) continue;
            var value = column.orElseThrow();
            Short id = ids.get(value.blockName());
            if (id == null) {
                id = (short) names.size();
                ids.put(value.blockName(), id);
                names.add(value.blockName());
                bytes += 64L + 2L * value.blockName().length();
            }
            int index = x + z * 16;
            identities[index] = id;
            heights[index] = value.y();
            var color = BlockColorPalette.resolve(value.blockName());
            colors[index] = color.rgb();
            if (!color.known()) unknown.set(index);
            populated++;
        }
        this.palette = List.copyOf(names);
        this.markers = List.copyOf(markers);
        this.markerDiagnostic = markerDiagnostic;
        this.populatedColumns = populated;
        this.estimatedBytes = bytes + markers.stream().mapToLong(m -> 192L + MapPersistence.textBytes(m.dimensionId())).sum() + 2L * markerDiagnostic.length();
    }

    // Parents sum unshaded child statistics, never rescale a shaded PNG.
    private synchronized long[] level(int level) {
        if (levels[level - 1] != null) return levels[level - 1];
        int side = 16 >> level;
        long[] values = new long[side * side * 5];
        long[] child = level == 1 ? null : level(level - 1);
        for (int z = 0; z < side * 2; z++) for (int x = 0; x < side * 2; x++) {
            int target = (x / 2 + z / 2 * side) * 5;
            int source = x + z * side * 2;
            if (child == null) {
                if (identities[source] < 0) continue;
                values[target] += colors[source] >>> 16 & 255;
                values[target + 1] += colors[source] >>> 8 & 255;
                values[target + 2] += colors[source] & 255;
                values[target + 3] += heights[source]; values[target + 4]++;
            } else for (int c = 0; c < 5; c++) values[target + c] += child[source * 5 + c];
        }
        return levels[level - 1] = values;
    }

    void renderInto(MapTileRenderer renderer, MapTileBounds bounds, MapZoomLevel zoom, int chunkX, int chunkZ) {
        int step = zoom.blocksPerPixel();
        if (step == 1) {
            for (int i = 0; i < 256; i++) if (identities[i] >= 0)
                renderer.accept(bounds, zoom, (long) chunkX * 16 + i % 16,
                        (long) chunkZ * 16 + i / 16, colors[i], heights[i]);
        } else {
            long[] values = level(Integer.numberOfTrailingZeros(step));
            int side = 16 / step;
            for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
                int i = (x + z * side) * 5;
                renderer.acceptAggregate(bounds, zoom, (long) chunkX * 16 + x * step,
                        (long) chunkZ * 16 + z * step, values[i], values[i + 1], values[i + 2],
                        values[i + 3], (int) values[i + 4]);
            }
        }
    }

    void write(java.io.DataOutput out) throws java.io.IOException {
        out.writeInt(palette.size()); for (String id : palette) out.writeUTF(id);
        for (int i = 0; i < 256; i++) {
            out.writeShort(identities[i]); out.writeInt(colors[i]); out.writeInt(heights[i]);
            out.writeBoolean(unknown.get(i));
        }
        out.writeUTF(markerDiagnostic); out.writeInt(markers.size());
        for (MapMarker m : markers) {
            out.writeUTF(m.type().name()); out.writeUTF(m.dimensionId());
            out.writeInt(m.x()); out.writeInt(m.y()); out.writeInt(m.z());
            out.writeInt(m.minX()); out.writeInt(m.minY()); out.writeInt(m.minZ());
            out.writeInt(m.maxX()); out.writeInt(m.maxY()); out.writeInt(m.maxZ());
        }
    }

    ChunkSurfaceSummary(java.io.DataInput in) throws java.io.IOException {
        int count = in.readInt();
        if (count < 0 || count > 256) throw new java.io.IOException("Invalid palette size");
        var names = new ArrayList<String>(); long bytes = 8192;
        for (int i = 0; i < count; i++) { String id = in.readUTF(); names.add(id); bytes += 64 + 2L * id.length(); }
        palette = List.copyOf(names); int populated = 0;
        for (int i = 0; i < 256; i++) {
            identities[i] = in.readShort(); colors[i] = in.readInt(); heights[i] = in.readInt();
            if (in.readBoolean()) unknown.set(i);
            if (identities[i] < -1 || identities[i] >= count || (identities[i] < 0 && unknown.get(i)))
                throw new java.io.IOException("Invalid column identity");
            if (identities[i] >= 0) populated++;
        }
        markerDiagnostic = in.readUTF(); int markerCount = in.readInt();
        if (markerCount < 0 || markerCount > 4096) throw new java.io.IOException("Invalid marker count");
        var restored = new ArrayList<MapMarker>();
        for (int i = 0; i < markerCount; i++) restored.add(new MapMarker(
                MapMarkerType.valueOf(in.readUTF()), in.readUTF(), in.readInt(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()));
        markers = List.copyOf(restored); populatedColumns = populated;
        estimatedBytes = bytes + markers.stream().mapToLong(m -> 192L + MapPersistence.textBytes(m.dimensionId())).sum() + 2L * markerDiagnostic.length();
    }

    String blockId(int column) { return identities[column] < 0 ? null : palette.get(identities[column]); }
    String colorDiagnostic(int column) { return unknown.get(column) ? "UNKNOWN_2D_COLOR" : ""; }
    List<MapMarker> markers() { return markers; }
    String markerDiagnostic() { return markerDiagnostic; }
    int populatedColumns() { return populatedColumns; }
    int unknownColumns() { return unknown.cardinality(); }
    long estimatedBytes() { return estimatedBytes; }
}
