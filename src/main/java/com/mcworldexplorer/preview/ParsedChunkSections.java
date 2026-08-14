package com.mcworldexplorer.preview;

import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import com.mcworldexplorer.voxel.io.ChunkNbtDecoder;
import com.mcworldexplorer.voxel.io.ChunkStorageLayout;
import com.mcworldexplorer.voxel.io.DecodedChunk;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ParsedChunkSections {
    private final ChunkSurfaceLayout layout;
    private final VoxelChunk chunk;
    private final List<ChunkSectionView> sections;
    private final Map<Integer, ChunkSectionView> sectionsByY;

    private ParsedChunkSections(
            ChunkSurfaceLayout layout,
            VoxelChunk chunk) {
        this.layout = layout;
        this.chunk = chunk;
        this.sections = chunk.sections().stream().map(ChunkSectionView::new).toList();
        Map<Integer, ChunkSectionView> byY = new HashMap<>();
        for (ChunkSectionView section : sections) {
            byY.put(section.sectionY(), section);
        }
        sectionsByY = Map.copyOf(byY);
    }

    public static ParsedChunkSections read(RegionChunkData chunkData)
            throws SurfaceSamplingException {
        if (chunkData == null) {
            throw new IllegalArgumentException("chunkData must not be null");
        }
        try (InputStream input = chunkData.openNbtStream()) {
            return fromDecoded(ChunkNbtDecoder.read(input));
        } catch (ChunkDecodeException e) {
            throw translate(e);
        } catch (SurfaceSamplingException e) {
            throw e;
        } catch (IOException e) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_NBT,
                    "failed to close chunk NBT input",
                    e);
        }
    }

    static ParsedChunkSections read(InputStream input) throws SurfaceSamplingException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        try {
            return fromDecoded(ChunkNbtDecoder.read(input));
        } catch (ChunkDecodeException e) {
            throw translate(e);
        }
    }

    public ChunkSurfaceLayout layout() {
        return layout;
    }

    public List<ChunkSectionView> sections() {
        return sections;
    }

    public Optional<DimensionHeightRange> sectionRange() {
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        int minSectionY = sections.stream()
                .mapToInt(ChunkSectionView::sectionY)
                .min()
                .orElseThrow();
        int maxSectionY = sections.stream()
                .mapToInt(ChunkSectionView::sectionY)
                .max()
                .orElseThrow();
        return Optional.of(new DimensionHeightRange(
                Math.multiplyExact(minSectionY, 16),
                Math.addExact(Math.multiplyExact(maxSectionY, 16), 15)));
    }

    public String blockName(int localX, int y, int localZ)
            throws SurfaceSamplingException {
        try {
            return chunk.blockState(localX, y, localZ).name();
        } catch (ChunkDecodeException e) {
            throw translate(e);
        }
    }

    private static ParsedChunkSections fromDecoded(DecodedChunk decoded) {
        ChunkSurfaceLayout layout = decoded.layout() == ChunkStorageLayout.MODERN_ROOT
                ? ChunkSurfaceLayout.MODERN_ROOT
                : ChunkSurfaceLayout.LEVEL_PALETTE;
        return new ParsedChunkSections(layout, decoded.chunk());
    }

    private static SurfaceSamplingException translate(ChunkDecodeException failure) {
        SurfaceSamplingException.Reason reason = switch (failure.reason()) {
            case INVALID_NBT, CHUNK_COORDINATE_MISMATCH ->
                    SurfaceSamplingException.Reason.INVALID_NBT;
            case UNSUPPORTED_CHUNK_LAYOUT ->
                    SurfaceSamplingException.Reason.UNSUPPORTED_CHUNK_LAYOUT;
            case INVALID_SECTION -> SurfaceSamplingException.Reason.INVALID_SECTION;
            case INVALID_PALETTE, INVALID_PALETTE_PROPERTY ->
                    SurfaceSamplingException.Reason.INVALID_PALETTE;
            case INVALID_BLOCK_STATE_STORAGE ->
                    SurfaceSamplingException.Reason.INVALID_BLOCK_STATE_STORAGE;
            case PALETTE_INDEX_OUT_OF_RANGE ->
                    SurfaceSamplingException.Reason.PALETTE_INDEX_OUT_OF_RANGE;
        };
        return new SurfaceSamplingException(reason, failure.getMessage(), failure);
    }
}
