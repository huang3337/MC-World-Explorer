package com.mcworldexplorer.preview;

import com.mcworldexplorer.region.RegionChunkData;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class SurfaceSampler {
    public ChunkSurface sample(RegionChunkData chunkData) throws SurfaceSamplingException {
        return sample(chunkData, PreviewLayer.surfaceOverview());
    }

    public ChunkSurface sample(
            RegionChunkData chunkData,
            PreviewLayer layer) throws SurfaceSamplingException {
        if (chunkData == null) {
            throw new IllegalArgumentException("chunkData must not be null");
        }
        return sample(ParsedChunkSections.read(chunkData), layer);
    }

    public ChunkSurface sample(
            ParsedChunkSections parsed,
            PreviewLayer layer) throws SurfaceSamplingException {
        if (parsed == null || layer == null) {
            throw new IllegalArgumentException("parsed chunk and layer must not be null");
        }
        List<ChunkSectionView> sections = new ArrayList<>(parsed.sections());
        ChunkSurface surface = new ChunkSurface(parsed.layout());
        if (layer.isSurfaceOverview()) {
            sections.sort(Comparator.comparingInt(ChunkSectionView::sectionY).reversed());
            for (ChunkSectionView section : sections) {
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
        return sample(input, PreviewLayer.surfaceOverview());
    }

    ChunkSurface sample(InputStream input, PreviewLayer layer) throws SurfaceSamplingException {
        return sample(ParsedChunkSections.read(input), layer);
    }

    Optional<DimensionHeightRange> sectionRange(RegionChunkData chunkData)
            throws SurfaceSamplingException {
        if (chunkData == null) {
            throw new IllegalArgumentException("chunkData must not be null");
        }
        return ParsedChunkSections.read(chunkData).sectionRange();
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
        for (int localY = 15; localY >= 0; localY--) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    if (surface.hasColumn(localX, localZ)) {
                        continue;
                    }
                    String blockName = section.blockName(localX, localY, localZ);
                    if (!isAir(blockName)) {
                        surface.setColumn(
                                localX,
                                localZ,
                                blockName,
                                section.sectionY() * 16 + localY);
                    }
                }
            }
        }
    }

    private static void fillHeightBand(
            ChunkSurface surface,
            ParsedChunkSections parsed,
            PreviewLayer layer) throws SurfaceSamplingException {
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                for (int y = layer.maxY(); y >= layer.minY(); y--) {
                    String ground = parsed.blockName(localX, y, localZ);
                    if (!isAir(ground)
                            && isAir(parsed.blockName(localX, y + 1, localZ))
                            && isAir(parsed.blockName(localX, y + 2, localZ))) {
                        surface.setColumn(localX, localZ, ground, y);
                        break;
                    }
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
