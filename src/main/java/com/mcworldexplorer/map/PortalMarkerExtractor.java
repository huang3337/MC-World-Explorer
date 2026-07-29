package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.ChunkSectionView;
import com.mcworldexplorer.preview.ParsedChunkSections;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.SurfaceSamplingException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PortalMarkerExtractor {
    private static final String PORTAL_BLOCK = "minecraft:nether_portal";

    public List<MapMarker> extract(
            ParsedChunkSections parsed,
            PreviewLayer layer,
            String dimensionId,
            int chunkX,
            int chunkZ) throws SurfaceSamplingException {
        if (parsed == null || layer == null || dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("parsed chunk, layer and dimension must be present");
        }
        Set<BlockPosition> portalBlocks = new HashSet<>();
        for (ChunkSectionView section : parsed.sections()) {
            if (!section.contains(PORTAL_BLOCK)) {
                continue;
            }
            int sectionMinY = section.sectionY() * 16;
            int minLocalY = layer.isSurfaceOverview()
                    ? 0
                    : Math.max(0, layer.minY() - sectionMinY);
            int maxLocalY = layer.isSurfaceOverview()
                    ? 15
                    : Math.min(15, layer.maxY() - sectionMinY);
            if (minLocalY > maxLocalY) {
                continue;
            }
            for (int localY = minLocalY; localY <= maxLocalY; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        if (PORTAL_BLOCK.equals(section.blockName(localX, localY, localZ))) {
                            portalBlocks.add(new BlockPosition(
                                    chunkX * 16 + localX,
                                    sectionMinY + localY,
                                    chunkZ * 16 + localZ));
                        }
                    }
                }
            }
        }
        return connectedMarkers(portalBlocks, dimensionId);
    }

    private static List<MapMarker> connectedMarkers(
            Set<BlockPosition> blocks,
            String dimensionId) {
        Set<BlockPosition> remaining = new HashSet<>(blocks);
        List<MapMarker> markers = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPosition start = remaining.iterator().next();
            remaining.remove(start);
            ArrayDeque<BlockPosition> queue = new ArrayDeque<>();
            queue.add(start);
            int minX = start.x;
            int minY = start.y;
            int minZ = start.z;
            int maxX = start.x;
            int maxY = start.y;
            int maxZ = start.z;
            while (!queue.isEmpty()) {
                BlockPosition current = queue.removeFirst();
                minX = Math.min(minX, current.x);
                minY = Math.min(minY, current.y);
                minZ = Math.min(minZ, current.z);
                maxX = Math.max(maxX, current.x);
                maxY = Math.max(maxY, current.y);
                maxZ = Math.max(maxZ, current.z);
                for (BlockPosition neighbor : current.neighbors()) {
                    if (remaining.remove(neighbor)) {
                        queue.addLast(neighbor);
                    }
                }
            }
            markers.add(MapMarker.portal(
                    dimensionId,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ));
        }
        return List.copyOf(markers);
    }

    private record BlockPosition(int x, int y, int z) {
        List<BlockPosition> neighbors() {
            return List.of(
                    new BlockPosition(x - 1, y, z),
                    new BlockPosition(x + 1, y, z),
                    new BlockPosition(x, y - 1, z),
                    new BlockPosition(x, y + 1, z),
                    new BlockPosition(x, y, z - 1),
                    new BlockPosition(x, y, z + 1));
        }
    }
}
