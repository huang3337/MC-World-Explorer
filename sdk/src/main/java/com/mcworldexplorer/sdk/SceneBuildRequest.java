package com.mcworldexplorer.sdk;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.resource.ModelRuleRegistry;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration for building a 3D {@link com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot}.
 */
public final class SceneBuildRequest {
    private final String dimensionId;
    private final ChunkRectangle chunks;
    private final Path clientJar;
    private final String minecraftVersion;
    private final Path instanceDirectory;
    private final ModelRuleRegistry modelRules;
    private final CancellationSignal cancellation;

    private SceneBuildRequest(Builder builder) {
        this.chunks = Objects.requireNonNull(builder.chunks, "chunks");
        if (!this.chunks.isSupportedTarget()) {
            throw new IllegalArgumentException(
                    "chunks must fit within " + ChunkRectangle.MAX_TARGET_SIZE + " chunks per side and "
                            + ChunkRectangle.MAX_TARGET_CHUNKS + " chunks total");
        }
        this.dimensionId = WorldDimension.normalizeId(builder.dimensionId);
        this.clientJar = builder.clientJar == null ? null : builder.clientJar.toAbsolutePath().normalize();
        this.minecraftVersion = builder.minecraftVersion == null ? null : builder.minecraftVersion.trim();
        this.instanceDirectory = builder.instanceDirectory == null ? null : builder.instanceDirectory.toAbsolutePath().normalize();
        if (this.clientJar != null && (this.minecraftVersion == null || this.minecraftVersion.isBlank())) {
            throw new IllegalArgumentException("minecraftVersion must be non-blank when clientJar is provided");
        }
        if (this.clientJar == null && this.instanceDirectory != null) {
            throw new IllegalArgumentException("instanceDirectory requires clientJar and minecraftVersion");
        }
        this.modelRules = builder.modelRules == null ? ModelRuleRegistry.defaults() : builder.modelRules;
        this.cancellation = builder.cancellation == null ? CancellationSignal.NONE : builder.cancellation;
    }

    /**
     * Creates a default overworld solid-color scene request requiring no external Minecraft client JAR.
     *
     * @param chunks target chunk rectangle (up to 32 chunks per side and 256 chunks total)
     * @return scene build request
     */
    public static SceneBuildRequest of(ChunkRectangle chunks) {
        return builder(chunks).build();
    }

    /**
     * Creates a request builder for the given target chunk rectangle.
     *
     * @param chunks target chunk rectangle
     * @return new builder
     */
    public static Builder builder(ChunkRectangle chunks) {
        return new Builder(chunks);
    }

    /**
     * Creates a request builder covering the inclusive block coordinate rectangle.
     *
     * @param minBlockX inclusive minimum block X
     * @param minBlockZ inclusive minimum block Z
     * @param maxBlockX inclusive maximum block X
     * @param maxBlockZ inclusive maximum block Z
     * @return new builder targeting all chunks intersecting the block rectangle
     */
    public static Builder builderForBlockRange(long minBlockX, long minBlockZ, long maxBlockX, long maxBlockZ) {
        if (minBlockX > maxBlockX || minBlockZ > maxBlockZ) {
            throw new IllegalArgumentException("block coordinate bounds are reversed");
        }
        try {
            int minChunkX = Math.toIntExact(Math.floorDiv(minBlockX, 16L));
            int minChunkZ = Math.toIntExact(Math.floorDiv(minBlockZ, 16L));
            int maxChunkX = Math.toIntExact(Math.floorDiv(maxBlockX, 16L));
            int maxChunkZ = Math.toIntExact(Math.floorDiv(maxBlockZ, 16L));
            return new Builder(ChunkRectangle.target(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("block coordinate range overflows supported chunk bounds", overflow);
        }
    }

    public String dimensionId() {
        return dimensionId;
    }

    public ChunkRectangle chunks() {
        return chunks;
    }

    public Optional<Path> clientJar() {
        return Optional.ofNullable(clientJar);
    }

    public Optional<String> minecraftVersion() {
        return Optional.ofNullable(minecraftVersion);
    }

    public Optional<Path> instanceDirectory() {
        return Optional.ofNullable(instanceDirectory);
    }

    public ModelRuleRegistry modelRules() {
        return modelRules;
    }

    public CancellationSignal cancellation() {
        return cancellation;
    }

    public static final class Builder {
        private final ChunkRectangle chunks;
        private String dimensionId = WorldDimension.OVERWORLD_ID;
        private Path clientJar;
        private String minecraftVersion;
        private Path instanceDirectory;
        private ModelRuleRegistry modelRules = ModelRuleRegistry.defaults();
        private CancellationSignal cancellation = CancellationSignal.NONE;

        private Builder(ChunkRectangle chunks) {
            this.chunks = Objects.requireNonNull(chunks, "chunks");
        }

        public Builder dimensionId(String dimensionId) {
            this.dimensionId = dimensionId;
            return this;
        }

        public Builder clientJar(Path clientJar, String minecraftVersion) {
            this.clientJar = Objects.requireNonNull(clientJar, "clientJar");
            if (minecraftVersion == null || minecraftVersion.isBlank()) {
                throw new IllegalArgumentException("minecraftVersion must not be blank");
            }
            this.minecraftVersion = minecraftVersion;
            return this;
        }

        public Builder instanceDirectory(Path instanceDirectory) {
            this.instanceDirectory = instanceDirectory;
            return this;
        }

        public Builder modelRules(ModelRuleRegistry modelRules) {
            this.modelRules = Objects.requireNonNull(modelRules, "modelRules");
            return this;
        }

        public Builder cancellation(CancellationSignal cancellation) {
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            return this;
        }

        public SceneBuildRequest build() {
            return new SceneBuildRequest(this);
        }
    }
}
