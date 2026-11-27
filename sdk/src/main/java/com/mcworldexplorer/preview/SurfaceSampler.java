package com.mcworldexplorer.preview;

import com.mcworldexplorer.nbt.SavedLegacyIdMap;
import com.mcworldexplorer.region.RegionChunkData;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

public final class SurfaceSampler {
    public ChunkSurface sample(RegionChunkData chunkData) throws SurfaceSamplingException {
        return sample(chunkData, PreviewLayer.surfaceOverview(), SavedLegacyIdMap.vanillaOnly());
    }

    public ChunkSurface sample(
            RegionChunkData chunkData,
            PreviewLayer layer) throws SurfaceSamplingException {
        return sample(chunkData, layer, SavedLegacyIdMap.vanillaOnly());
    }

    public ChunkSurface sample(
            RegionChunkData chunkData,
            PreviewLayer layer,
            SavedLegacyIdMap legacyIdMap) throws SurfaceSamplingException {
        if (chunkData == null) {
            throw new IllegalArgumentException("chunkData must not be null");
        }
        return sample(ParsedChunkSections.read(chunkData, legacyIdMap), layer);
    }

    public ChunkSurface sample(
            ParsedChunkSections parsed,
            PreviewLayer layer) throws SurfaceSamplingException {
        if (parsed == null || layer == null) {
            throw new IllegalArgumentException("parsed chunk and layer must not be null");
        }
        ChunkSurface surface = new ChunkSurface(parsed.layout());
        if (layer.isSurfaceOverview()) {
            for (ChunkSectionView section : parsed.sections().reversed()) {
                fillSurface(surface, section);
                if (surface.getPopulatedColumnCount() == ChunkSurface.WIDTH * ChunkSurface.WIDTH) {
                    break;
                }
            }
        } else {
            fillHeightBand(surface, parsed, layer);
        }
        return surface;
    }

    ChunkSurface sample(InputStream input) throws SurfaceSamplingException {
        return sample(input, PreviewLayer.surfaceOverview(), SavedLegacyIdMap.vanillaOnly());
    }

    ChunkSurface sample(InputStream input, SavedLegacyIdMap legacyIdMap) throws SurfaceSamplingException {
        return sample(input, PreviewLayer.surfaceOverview(), legacyIdMap);
    }

    ChunkSurface sample(InputStream input, PreviewLayer layer) throws SurfaceSamplingException {
        return sample(input, layer, SavedLegacyIdMap.vanillaOnly());
    }

    ChunkSurface sample(InputStream input, PreviewLayer layer, SavedLegacyIdMap legacyIdMap)
            throws SurfaceSamplingException {
        return sample(ParsedChunkSections.read(input, legacyIdMap), layer);
    }

    Optional<DimensionHeightRange> sectionRange(RegionChunkData chunkData)
            throws SurfaceSamplingException {
        return sectionRange(chunkData, SavedLegacyIdMap.vanillaOnly());
    }

    Optional<DimensionHeightRange> sectionRange(RegionChunkData chunkData, SavedLegacyIdMap legacyIdMap)
            throws SurfaceSamplingException {
        if (chunkData == null) {
            throw new IllegalArgumentException("chunkData must not be null");
        }
        return ParsedChunkSections.read(chunkData, legacyIdMap).sectionRange();
    }

    Optional<DimensionHeightRange> sectionRange(InputStream input)
            throws SurfaceSamplingException {
        return ParsedChunkSections.read(input).sectionRange();
    }

    /** Samples normalized raw chunk data through the same 2D surface rules, without 3D materials. */
    public void forEachSurfaceColumn(com.mcworldexplorer.voxel.data.VoxelChunk chunk,
                                     SurfaceColumnConsumer consumer) throws SurfaceSamplingException {
        java.util.Objects.requireNonNull(chunk, "chunk");
        java.util.Objects.requireNonNull(consumer, "consumer");
        // Internal accumulator only; the original storage layout is irrelevant to decoded columns.
        ChunkSurface surface = new ChunkSurface(ChunkSurfaceLayout.MODERN_ROOT);
        for (var section : chunk.sections().reversed()) {
            fillSurface(surface, new ChunkSectionView(section));
            if (surface.getPopulatedColumnCount() == 256) break;
        }
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var column = surface.getColumn(x, z);
            if (column.isPresent()) consumer.accept(x, z, column.orElseThrow());
        }
    }

    @FunctionalInterface
    public interface SurfaceColumnConsumer {
        void accept(int localX, int localZ, SurfaceColumn column);
    }

    private static void fillSurface(ChunkSurface surface, ChunkSectionView section)
            throws SurfaceSamplingException {
        var uniform = section.uniformBlockName();
        if (uniform.isPresent() && isAir(uniform.get())) return;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                if (surface.hasColumn(localX, localZ)) continue;
                for (int localY = 15; localY >= 0; localY--) {
                    String blockName = uniform.isPresent() ? uniform.get() : section.blockName(localX, localY, localZ);
                    if (!isAir(blockName)) {
                        surface.setColumn(
                                localX,
                                localZ,
                                blockName,
                                section.sectionY() * 16 + localY);
                        break;
                    }
                }
            }
        }
    }

    private static void fillHeightBand(
            ChunkSurface surface,
            ParsedChunkSections parsed,
            PreviewLayer layer) throws SurfaceSamplingException {
        // At most 32 rows. Resolve each section once for all 256 columns.
        ChunkSectionView[] rows = new ChunkSectionView[layer.maxY() - layer.minY() + 1];
        for (ChunkSectionView section : parsed.sections()) {
            long start = 16L * section.sectionY() - layer.minY();
            if (start < rows.length && start + 16 > 0) {
                Arrays.fill(rows, (int) Math.max(0, start), (int) Math.min(rows.length, start + 16), section);
            }
        }
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                String above = null, twoAbove = null;
                for (int row = rows.length - 1; row >= 0; row--) {
                    int y = layer.minY() + row;
                    String ground = rows[row] == null ? "minecraft:air" : rows[row].blockName(localX, y & 15, localZ);
                    if (!isAir(ground)) {
                        if (above == null) above = parsed.blockName(localX, y + 1, localZ);
                        if (isAir(above) && isAir(twoAbove == null ? parsed.blockName(localX, y + 2, localZ) : twoAbove)) {
                            surface.setColumn(localX, localZ, ground, y);
                            break;
                        }
                    }
                    twoAbove = above;
                    above = ground;
                }
            }
        }
    }

    private static boolean isAir(String blockName) {
        return blockName.equals("minecraft:air")
                || blockName.equals("minecraft:cave_air")
                || blockName.equals("minecraft:void_air");
    }
}
