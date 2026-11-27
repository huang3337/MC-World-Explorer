package com.mcworldexplorer.sdk;

import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.io.CancellationSignal;

import java.util.Objects;

/**
 * Immutable configuration for headless 2D top-down map rendering.
 */
public final class MapRenderRequest {
    public static final int MAX_OUTPUT_DIMENSION = 4096;
    public static final int MAX_OUTPUT_PIXELS = 16_777_216;
    public static final int MAX_BLOCKS_PER_PIXEL = 64;
    public static final int MAX_SAMPLED_CHUNKS = 16_384;

    private final String dimensionId;
    private final PreviewLayer layer;
    private final long minBlockX;
    private final long minBlockZ;
    private final long maxBlockX;
    private final long maxBlockZ;
    private final int minChunkX;
    private final int maxChunkX;
    private final int minChunkZ;
    private final int maxChunkZ;
    private final int blocksPerPixel;
    private final int imageWidth;
    private final int imageHeight;
    private final CancellationSignal cancellation;

    private MapRenderRequest(Builder builder) {
        if (builder.minBlockX > builder.maxBlockX || builder.minBlockZ > builder.maxBlockZ) {
            throw new IllegalArgumentException("block coordinate bounds are reversed");
        }
        if (builder.blocksPerPixel < 1 || builder.blocksPerPixel > MAX_BLOCKS_PER_PIXEL) {
            throw new IllegalArgumentException(
                    "blocksPerPixel must be between 1 and " + MAX_BLOCKS_PER_PIXEL);
        }
        long spanX;
        long spanZ;
        try {
            spanX = Math.addExact(Math.subtractExact(builder.maxBlockX, builder.minBlockX), 1L);
            spanZ = Math.addExact(Math.subtractExact(builder.maxBlockZ, builder.minBlockZ), 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("block coordinate range overflows supported bounds", overflow);
        }
        long width = (spanX + builder.blocksPerPixel - 1L) / builder.blocksPerPixel;
        long height = (spanZ + builder.blocksPerPixel - 1L) / builder.blocksPerPixel;
        if (width < 1 || height < 1 || width > MAX_OUTPUT_DIMENSION || height > MAX_OUTPUT_DIMENSION
                || width * height > MAX_OUTPUT_PIXELS) {
            throw new IllegalArgumentException(
                    "output image dimensions exceed limits (" + width + "x" + height
                            + ", max " + MAX_OUTPUT_DIMENSION + " per side and " + MAX_OUTPUT_PIXELS + " pixels)");
        }
        int parsedMinChunkX;
        int parsedMaxChunkX;
        int parsedMinChunkZ;
        int parsedMaxChunkZ;
        long chunkCount;
        try {
            long paddedMinX = Math.subtractExact(builder.minBlockX, (long) builder.blocksPerPixel);
            long paddedMinZ = Math.subtractExact(builder.minBlockZ, (long) builder.blocksPerPixel);
            parsedMinChunkX = Math.toIntExact(Math.floorDiv(paddedMinX, 16L));
            parsedMaxChunkX = Math.toIntExact(Math.floorDiv(builder.maxBlockX, 16L));
            parsedMinChunkZ = Math.toIntExact(Math.floorDiv(paddedMinZ, 16L));
            parsedMaxChunkZ = Math.toIntExact(Math.floorDiv(builder.maxBlockZ, 16L));
            long chunksX = Math.addExact(Math.subtractExact((long) parsedMaxChunkX, (long) parsedMinChunkX), 1L);
            long chunksZ = Math.addExact(Math.subtractExact((long) parsedMaxChunkZ, (long) parsedMinChunkZ), 1L);
            chunkCount = Math.multiplyExact(chunksX, chunksZ);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("sampled chunk range overflows supported bounds", overflow);
        }
        if (chunkCount > MAX_SAMPLED_CHUNKS) {
            throw new IllegalArgumentException(
                    "requested region requires sampling " + chunkCount + " chunks (max " + MAX_SAMPLED_CHUNKS + ")");
        }

        this.dimensionId = WorldDimension.normalizeId(builder.dimensionId);
        this.layer = Objects.requireNonNull(builder.layer, "layer");
        this.minBlockX = builder.minBlockX;
        this.minBlockZ = builder.minBlockZ;
        this.maxBlockX = builder.maxBlockX;
        this.maxBlockZ = builder.maxBlockZ;
        this.minChunkX = parsedMinChunkX;
        this.maxChunkX = parsedMaxChunkX;
        this.minChunkZ = parsedMinChunkZ;
        this.maxChunkZ = parsedMaxChunkZ;
        this.blocksPerPixel = builder.blocksPerPixel;
        this.imageWidth = (int) width;
        this.imageHeight = (int) height;
        this.cancellation = builder.cancellation == null ? CancellationSignal.NONE : builder.cancellation;
    }

    /**
     * Creates a default 1:1 overworld surface map request for the inclusive block rectangle.
     */
    public static MapRenderRequest ofBlockRange(long minBlockX, long minBlockZ, long maxBlockX, long maxBlockZ) {
        return builder(minBlockX, minBlockZ, maxBlockX, maxBlockZ).build();
    }

    /**
     * Creates a default 1:1 overworld surface map request covering the given chunk rectangle.
     */
    public static MapRenderRequest ofChunks(ChunkRectangle chunks) {
        Objects.requireNonNull(chunks, "chunks");
        long minX = Math.multiplyExact((long) chunks.minX(), 16L);
        long minZ = Math.multiplyExact((long) chunks.minZ(), 16L);
        long maxX = Math.addExact(Math.multiplyExact((long) chunks.maxX(), 16L), 15L);
        long maxZ = Math.addExact(Math.multiplyExact((long) chunks.maxZ(), 16L), 15L);
        return builder(minX, minZ, maxX, maxZ).build();
    }

    public static Builder builder(long minBlockX, long minBlockZ, long maxBlockX, long maxBlockZ) {
        return new Builder(minBlockX, minBlockZ, maxBlockX, maxBlockZ);
    }

    public String dimensionId() {
        return dimensionId;
    }

    public PreviewLayer layer() {
        return layer;
    }

    public long minBlockX() {
        return minBlockX;
    }

    public long minBlockZ() {
        return minBlockZ;
    }

    public long maxBlockX() {
        return maxBlockX;
    }

    public long maxBlockZ() {
        return maxBlockZ;
    }

    int minChunkX() {
        return minChunkX;
    }

    int maxChunkX() {
        return maxChunkX;
    }

    int minChunkZ() {
        return minChunkZ;
    }

    int maxChunkZ() {
        return maxChunkZ;
    }

    public int blocksPerPixel() {
        return blocksPerPixel;
    }

    public int imageWidth() {
        return imageWidth;
    }

    public int imageHeight() {
        return imageHeight;
    }

    public CancellationSignal cancellation() {
        return cancellation;
    }

    public static final class Builder {
        private final long minBlockX;
        private final long minBlockZ;
        private final long maxBlockX;
        private final long maxBlockZ;
        private String dimensionId = WorldDimension.OVERWORLD_ID;
        private PreviewLayer layer = PreviewLayer.surfaceOverview();
        private int blocksPerPixel = 1;
        private CancellationSignal cancellation = CancellationSignal.NONE;

        private Builder(long minBlockX, long minBlockZ, long maxBlockX, long maxBlockZ) {
            this.minBlockX = minBlockX;
            this.minBlockZ = minBlockZ;
            this.maxBlockX = maxBlockX;
            this.maxBlockZ = maxBlockZ;
        }

        public Builder dimensionId(String dimensionId) {
            this.dimensionId = dimensionId;
            return this;
        }

        public Builder layer(PreviewLayer layer) {
            this.layer = Objects.requireNonNull(layer, "layer");
            return this;
        }

        public Builder blocksPerPixel(int blocksPerPixel) {
            this.blocksPerPixel = blocksPerPixel;
            return this;
        }

        public Builder cancellation(CancellationSignal cancellation) {
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            return this;
        }

        public MapRenderRequest build() {
            return new MapRenderRequest(this);
        }
    }
}
