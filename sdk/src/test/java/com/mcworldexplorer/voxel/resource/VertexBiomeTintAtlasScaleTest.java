package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.mesh.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies V0.8.0 GPU vertex biome tinting and coplanar overlay decoupling:
 * 600+ distinct biome RGB gradients across grass_block, oak_leaves, short_grass, and water
 * must produce a constant-size untinted TextureAtlas with zero TEXTURE_FALLBACK degradation.
 */
class VertexBiomeTintAtlasScaleTest {
    @TempDir Path tempDir;

    private static final int BIOME_COUNT = 800;
    private static final BlockState GRASS = new BlockState("minecraft:grass_block", Map.of());
    private static final BlockState LEAVES = new BlockState("minecraft:oak_leaves", Map.of());
    private static final BlockState SHORT_GRASS = new BlockState("minecraft:short_grass", Map.of());
    private static final BlockState WATER = new BlockState("minecraft:water", Map.of("level", "0"));
    private static final BlockState FLOWING_WATER = new BlockState("minecraft:water", Map.of("level", "4"));

    @Test
    void sixHundredDistinctBiomeGradientsKeepConstantUntintedAtlasAndExactVertexColors() throws Exception {
        var source = syntheticSource();
        var area = buildMultiBiomeArea();

        var prepared = BlockTextureSet.load(source, area, CancellationSignal.NONE);
        var scene = new VoxelSceneBuilder(prepared).build(area, CancellationSignal.NONE).snapshot().orElseThrow();

        // 1. Constant-size atlas with untinted raw tiles only (grass_top, dirt, grass_side, grass_side_overlay,
        //    oak_leaves, short_grass, water_still, water_flow -> 8 unique tiles total).
        assertTrue(prepared.atlas().tileCount() <= 12,
                "atlas tile count should remain constant regardless of biome gradient count, got " + prepared.atlas().tileCount());
        assertTrue(prepared.atlas().height() <= 64,
                "atlas height should stay compact for 8 tiles, got " + prepared.atlas().height());

        // 2. Zero TEXTURE_FALLBACK and zero UNBOUND_TINT warnings.
        assertTrue(scene.warnings().stream().noneMatch(w -> w.code() == MeshWarning.Code.TEXTURE_FALLBACK),
                "unexpected TEXTURE_FALLBACK: " + scene.warnings());
        assertTrue(scene.warnings().stream().flatMap(w -> w.diagnostic().stream())
                        .noneMatch(d -> "UNBOUND_TINT".equals(d.reasonCode())),
                "unexpected UNBOUND_TINT: " + scene.warnings());

        // 3. grass_block keeps strictly 6 OPAQUE faces and forms a full 1x1x1 occlusion box.
        var grassSurfaces = prepared.surfaces(GRASS);
        assertNotNull(grassSurfaces);
        assertEquals(6, grassSurfaces.size());
        assertTrue(grassSurfaces.stream().allMatch(s -> s.layer() == RenderLayer.OPAQUE));
        assertEquals(1, ModelOcclusion.boxes(grassSurfaces).size());

        var northGrass = grassSurfaces.stream()
                .filter(s -> s.direction() == BlockModelResolver.Direction.NORTH)
                .findFirst().orElseThrow();
        assertTrue(northGrass.hasOverlay());
        assertEquals(-1, northGrass.tintIndex());
        assertEquals(0, northGrass.overlayTintIndex());

        // 4. Verify distinct per-position RGBs across all 600 biomes in MeshBatches.
        Set<Integer> opaqueVertexColors = new HashSet<>();
        Set<Integer> opaqueOverlayColors = new HashSet<>();
        Set<Integer> cutoutVertexColors = new HashSet<>();
        Set<Integer> fluidVertexColors = new HashSet<>();

        for (var chunk : scene.chunks()) {
            for (var batch : chunk.batches()) {
                if (batch.layer() == RenderLayer.OPAQUE) {
                    for (int rgb : batch.vertexRgb()) opaqueVertexColors.add(rgb);
                    for (int rgb : batch.overlayRgb()) if (rgb >= 0) opaqueOverlayColors.add(rgb);
                } else if (batch.layer() == RenderLayer.CUTOUT) {
                    for (int rgb : batch.vertexRgb()) cutoutVertexColors.add(rgb);
                } else if (batch.layer() == RenderLayer.FLUID) {
                    for (int rgb : batch.vertexRgb()) fluidVertexColors.add(rgb);
                }
            }
        }

        // Opaque batch contains untinted 0xFFFFFF (dirt/side bases) + 500+ distinct grass top colors.
        assertTrue(opaqueVertexColors.contains(0xFFFFFF), "grass side dirt base must remain untinted 0xFFFFFF");
        assertTrue(opaqueVertexColors.size() >= 500, "expected 500+ distinct grass top RGBs, got " + opaqueVertexColors.size());
        assertTrue(opaqueOverlayColors.size() >= 500, "expected 500+ distinct grass side overlay RGBs, got " + opaqueOverlayColors.size());
        assertTrue(cutoutVertexColors.size() >= 500, "expected 500+ distinct leaves/short_grass RGBs, got " + cutoutVertexColors.size());
        assertTrue(fluidVertexColors.size() >= 500, "expected 500+ distinct water RGBs, got " + fluidVertexColors.size());

        // 5. Spot-check exact resolved colors at specific coordinates for grass_block (base + overlay) and water.
        long hashedSeed = SavedBiomeSampler.obfuscateSeed(0L);
        for (int i : new int[]{0, 137, 350, 599, 750}) {
            int cx = i / 64;
            int localIndex = i % 64;
            int qx = localIndex & 3;
            int qz = (localIndex >> 2) & 3;
            int qy = (localIndex >> 4) & 3;
            long wx = cx * 16L + qx * 4L + 1;
            int wy = qy * 4 + 1;
            long wz = qz * 4L + 1;
            var sampled = SavedBiomeSampler.sample(hashedSeed, (int) wx, wy, (int) wz);
            int sampledBiome = sampledBiomeIndex(sampled.x(), Math.clamp(sampled.y(), 0, 3), sampled.z());
            int expectedGrass = grassRgb(sampledBiome);
            var upGrass = grassSurfaces.stream()
                    .filter(s -> s.direction() == BlockModelResolver.Direction.UP)
                    .findFirst().orElseThrow();
            assertEquals(expectedGrass, prepared.surfaceColor(upGrass, wx, wy, wz), "grass top at biome " + i);
            assertEquals(0xFFFFFF, prepared.surfaceColor(northGrass, wx, wy, wz), "grass side base at biome " + i);
            assertEquals(expectedGrass, prepared.overlayColor(northGrass, wx, wy, wz), "grass side overlay at biome " + i);

            var waterSampled = SavedBiomeSampler.sample(hashedSeed, (int) (wx + 1), wy, (int) wz);
            int expectedWater = waterRgb(sampledBiomeIndex(waterSampled.x(), Math.clamp(waterSampled.y(), 0, 3), waterSampled.z()));
            var waterSurfaces = prepared.surfaces(WATER);
            assertNotNull(waterSurfaces);
            assertEquals(expectedWater, prepared.surfaceColor(waterSurfaces.getFirst(), wx + 1, wy, wz), "water at biome " + i);
        }
    }

    private static int sampledBiomeIndex(int qx, int qy, int qz) {
        if (qx < 0 || qx >= 14 * 4 || qz < 0 || qz >= 4) return 0;
        int cx = qx / 4;
        int lqx = qx & 3;
        int cellIdx = (qy * 4 + qz) * 4 + lqx;
        return Math.min(BIOME_COUNT - 1, cx * 64 + cellIdx);
    }

    @Test
    void flowingWaterPreservesDynamicTintIndexThroughFluidGeometry() throws Exception {
        var source = syntheticSource();
        var area = buildSingleFlowingWaterArea();
        var prepared = BlockTextureSet.load(source, area, CancellationSignal.NONE);
        assertNotNull(prepared.surfaces(WATER), () -> "WATER fallback: " + prepared.fallbackReason(WATER));
        assertNotNull(prepared.surfaces(FLOWING_WATER), () -> "FLOWING_WATER fallback: " + prepared.fallbackReason(FLOWING_WATER));
        assertTrue(prepared.notes(WATER).stream().noneMatch(n -> n.contains("颜色未解析")), () -> "WATER notes: " + prepared.notes(WATER));
        var chunkMesh = new ChunkMesher(prepared).build(area, new ChunkCoordinate(0, 0), CancellationSignal.NONE);

        var fluidBatch = chunkMesh.batches().stream()
                .filter(b -> b.layer() == RenderLayer.FLUID)
                .findFirst().orElseThrow();
        assertTrue(fluidBatch.hasVertexColors(), "flowing water batch must carry per-vertex biome colors");
        for (int rgb : fluidBatch.vertexRgb()) {
            assertEquals(waterRgb(7), rgb, "flowing water vertices must preserve biome water tint");
        }
    }

    @Test
    void neighborOnlyContextualBlocksAndMissingBiomesDoNotEmitFalseUnboundTintAndCompositeSolidCutoutMerges() throws Exception {
        var baseSource = syntheticSource();
        var target = ChunkRectangle.target(0, 0, 0, 0);
        Map<ChunkCoordinate, ChunkLoadResult> chunks = new LinkedHashMap<>();
        var validBiome = BiomeSection.fromCells(Collections.nCopies(64, "fixture:biome_3"));
        var missingBiome = BiomeSection.fromCells(Collections.nCopies(64, "fixture:missing_biome"));
        for (ChunkCoordinate coord : target.expanded(1).coordinates()) {
            if (coord.equals(new ChunkCoordinate(0, 0))) {
                // Target chunk contains grass_block in a missing biome definition.
                long[] data = new long[256];
                setBlock(data, 2, 2, 2, 1);
                var chunk = new VoxelChunk(0, 0, List.of(new ChunkSection(0, List.of(BlockState.AIR, GRASS), data)), List.of(), Map.of(0, missingBiome));
                chunks.put(coord, ChunkLoadResult.success(coord, chunk, 0, 0));
            } else if (coord.equals(new ChunkCoordinate(1, 0))) {
                // Neighbor chunk border (x = 0) contains oak_leaves ONLY outside target.
                long[] data = new long[256];
                setBlock(data, 0, 2, 2, 1);
                var chunk = new VoxelChunk(1, 0, List.of(new ChunkSection(0, List.of(BlockState.AIR, LEAVES), data)), List.of(), Map.of(0, validBiome));
                chunks.put(coord, ChunkLoadResult.success(coord, chunk, 0, 0));
            } else {
                chunks.put(coord, ChunkLoadResult.success(coord,
                        new VoxelChunk(coord.x(), coord.z(), List.of(), List.of(), Map.of(0, validBiome)), 0, 0));
            }
        }
        var area = new VoxelAreaLoadResult(tempDir, WorldDimension.overworld(tempDir), target, chunks);
        var prepared = BlockTextureSet.load(baseSource, area, CancellationSignal.NONE);

        // Neighbor-only oak_leaves has a bound handler -> dynamic tintIndex 0 preserved, zero UNBOUND_TINT.
        assertNotNull(prepared.surfaces(LEAVES));
        assertEquals(0, prepared.surfaces(LEAVES).getFirst().tintIndex());
        assertTrue(prepared.diagnostics(LEAVES).stream().noneMatch(d -> "UNBOUND_TINT".equals(d.reasonCode())));

        // Target grass_block in missing biome reports TINT_UNRESOLVED (DEGRADED), NOT false UNBOUND_TINT.
        assertTrue(prepared.diagnostics(GRASS).stream().noneMatch(d -> "UNBOUND_TINT".equals(d.reasonCode())),
                () -> "grass_block has a bound handler and must not report UNBOUND_TINT: " + prepared.diagnostics(GRASS));
        var unresolved = prepared.diagnostics(GRASS).stream()
                .filter(d -> "TINT_UNRESOLVED".equals(d.reasonCode()))
                .findFirst().orElseThrow();
        assertEquals(BlockResourceDiagnostic.Severity.DEGRADED, unresolved.severity());

        // Composite model with solid base part + cutout tinted overlay part on same coplanar faces merges into 6 OPAQUE surfaces.
        var compositeState = new BlockState("minecraft:podzol", Map.of());
        Map<String, byte[]> compositeFiles = new HashMap<>();
        compositeFiles.put("assets/minecraft/textures/block/dirt.png", solidPng(0xFF8B5A2B));
        compositeFiles.put("assets/minecraft/textures/block/grass_block_side_overlay.png", halfTransparentPng(0xFFA0A0A0));
        compositeFiles.put("assets/minecraft/blockstates/podzol.json",
                utf8("{\"variants\":{\"\":{\"model\":\"minecraft:block/composite_turf\"}}}"));
        compositeFiles.put("assets/minecraft/models/block/composite_turf.json", utf8("""
                {
                  "loader": "neoforge:composite",
                  "children": {
                    "base": {
                      "render_type": "minecraft:solid",
                      "textures": {"all": "minecraft:block/dirt"},
                      "elements": [{
                        "from": [0, 0, 0], "to": [16, 16, 16],
                        "faces": {
                          "down":  {"texture": "#all", "cullface": "down"},
                          "up":    {"texture": "#all", "cullface": "up"},
                          "north": {"texture": "#all", "cullface": "north"},
                          "south": {"texture": "#all", "cullface": "south"},
                          "west":  {"texture": "#all", "cullface": "west"},
                          "east":  {"texture": "#all", "cullface": "east"}
                        }
                      }]
                    },
                    "overlay": {
                      "render_type": "minecraft:cutout",
                      "textures": {"mask": "minecraft:block/grass_block_side_overlay"},
                      "elements": [{
                        "from": [0, 0, 0], "to": [16, 16, 16],
                        "faces": {
                          "north": {"texture": "#mask", "cullface": "north"}
                        }
                      }]
                    }
                  }
                }
                """));
        BlockResourceSource compositeSource = new BlockResourceSource() {
            @Override public Optional<byte[]> read(ResourceLocation.Kind kind, ResourceLocation id) throws IOException {
                byte[] override = compositeFiles.get(id.entryName(kind));
                return override != null ? Optional.of(override) : baseSource.read(kind, id);
            }
            @Override public Optional<String> readJson(ResourceLocation.Kind kind, ResourceLocation id) throws IOException {
                byte[] override = compositeFiles.get(id.entryName(kind));
                return override != null ? Optional.of(new String(override, StandardCharsets.UTF_8)) : baseSource.readJson(kind, id);
            }
            @Override public ClientResourceArchive.Fingerprint fingerprint() { return baseSource.fingerprint(); }
        };
        var compositePrepared = BlockTextureSet.load(compositeSource, List.of(compositeState), CancellationSignal.NONE);
        var compositeSurfaces = compositePrepared.surfaces(compositeState);
        assertNotNull(compositeSurfaces, () -> compositePrepared.fallbackReason(compositeState));
        assertEquals(6, compositeSurfaces.size(), "coplanar solid base + cutout overlay in composite model must merge into 6 faces");
        assertTrue(compositeSurfaces.stream().allMatch(s -> s.layer() == RenderLayer.OPAQUE));
        assertEquals(1, ModelOcclusion.boxes(compositeSurfaces).size());
        var northComposite = compositeSurfaces.stream()
                .filter(s -> s.direction() == BlockModelResolver.Direction.NORTH)
                .findFirst().orElseThrow();
        assertTrue(northComposite.hasOverlay());
    }

    private static int grassRgb(int index) {
        return 0x208010 + ((index * 179) & 0x3F7F3F);
    }

    private static int foliageRgb(int index) {
        return 0x107010 + ((index * 193) & 0x3F7F3F);
    }

    private static int waterRgb(int index) {
        return 0x084090 + ((index * 211) & 0x3F7F5F);
    }

    private VoxelAreaLoadResult buildMultiBiomeArea() throws Exception {
        // 14 chunks in X (cx = 0..13, cz = 0), each chunk has 1 section (sectionY = 0) with 4x4x4 = 64 quart biome cells -> 896 cells >= 800 biomes.
        int chunkCountX = 14;
        var target = ChunkRectangle.target(0, 0, chunkCountX - 1, 0);
        Map<ChunkCoordinate, ChunkLoadResult> chunks = new LinkedHashMap<>();
        var palette = List.of(BlockState.AIR, GRASS, WATER, LEAVES, SHORT_GRASS);

        for (ChunkCoordinate coord : target.expanded(1).coordinates()) {
            if (!target.contains(coord)) {
                var empty = new VoxelChunk(coord.x(), coord.z(), List.of(), List.of(),
                        Map.of(0, BiomeSection.fromCells(Collections.nCopies(64, "fixture:biome_0"))));
                chunks.put(coord, ChunkLoadResult.success(coord, empty, 0, 0));
                continue;
            }
            long[] blockData = new long[256];
            List<String> biomeCells = new ArrayList<>(64);

            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        int cellIdx = (qy * 4 + qz) * 4 + qx;
                        int biomeId = Math.min(BIOME_COUNT - 1, coord.x() * 64 + cellIdx);
                        biomeCells.add("fixture:biome_" + biomeId);

                        // Place one of each tinted block inside the 4x4x4 quart cell with air around them so all faces are visible.
                        int bx = qx * 4 + 1;
                        int by = qy * 4 + 1;
                        int bz = qz * 4 + 1;
                        setBlock(blockData, bx, by, bz, 1);
                        setBlock(blockData, bx + 1, by, bz, 2);
                        setBlock(blockData, bx, by, bz + 1, 3);
                        setBlock(blockData, bx + 1, by, bz + 1, 4);
                    }
                }
            }
            var section = new ChunkSection(0, palette, blockData);
            var biomeSection = BiomeSection.fromCells(biomeCells);
            var chunk = new VoxelChunk(coord.x(), coord.z(), List.of(section), List.of(), Map.of(0, biomeSection));
            chunks.put(coord, ChunkLoadResult.success(coord, chunk, 0, 0));
        }
        return new VoxelAreaLoadResult(tempDir, WorldDimension.overworld(tempDir), target, chunks);
    }

    private VoxelAreaLoadResult buildSingleFlowingWaterArea() throws Exception {
        var target = ChunkRectangle.target(0, 0, 0, 0);
        Map<ChunkCoordinate, ChunkLoadResult> chunks = new LinkedHashMap<>();
        var biomeSection = BiomeSection.fromCells(Collections.nCopies(64, "fixture:biome_7"));
        var palette = List.of(BlockState.AIR, WATER, FLOWING_WATER);
        for (ChunkCoordinate coord : target.expanded(1).coordinates()) {
            if (!target.contains(coord)) {
                chunks.put(coord, ChunkLoadResult.success(coord,
                        new VoxelChunk(coord.x(), coord.z(), List.of(), List.of(), Map.of(0, biomeSection)), 0, 0));
                continue;
            }
            long[] blockData = new long[256];
            setBlock(blockData, 1, 1, 1, 1);
            setBlock(blockData, 2, 1, 1, 2);
            var chunk = new VoxelChunk(0, 0, List.of(new ChunkSection(0, palette, blockData)), List.of(), Map.of(0, biomeSection));
            chunks.put(coord, ChunkLoadResult.success(coord, chunk, 0, 0));
        }
        return new VoxelAreaLoadResult(tempDir, WorldDimension.overworld(tempDir), target, chunks);
    }

    private static void setBlock(long[] data, int x, int y, int z, int paletteIndex) {
        int blockIndex = y * 256 + z * 16 + x;
        int longIndex = blockIndex / 16;
        int bitOffset = (blockIndex % 16) * 4;
        data[longIndex] = (data[longIndex] & ~(0xFL << bitOffset)) | ((long) paletteIndex << bitOffset);
    }

    private static BlockResourceSource syntheticSource() throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        files.put("assets/minecraft/textures/block/grass_block_top.png", solidPng(0xFF949494));
        files.put("assets/minecraft/textures/block/dirt.png", solidPng(0xFF8B5A2B));
        files.put("assets/minecraft/textures/block/grass_block_side.png", solidPng(0xFF8B5A2B));
        files.put("assets/minecraft/textures/block/grass_block_side_overlay.png", halfTransparentPng(0xFFA0A0A0));
        files.put("assets/minecraft/textures/block/oak_leaves.png", halfTransparentPng(0xFF888888));
        files.put("assets/minecraft/textures/block/short_grass.png", halfTransparentPng(0xFF909090));
        files.put("assets/minecraft/textures/block/water_still.png", solidPng(0xC0FFFFFF));
        files.put("assets/minecraft/textures/block/water_flow.png", solidPng(0xC0EFEFEF));
        files.put("assets/minecraft/textures/colormap/grass.png", solidPng(256, 256, 0xFF79C05A));
        files.put("assets/minecraft/textures/colormap/foliage.png", solidPng(256, 256, 0xFF59AE30));

        files.put("assets/minecraft/blockstates/grass_block.json",
                utf8("{\"variants\":{\"\":{\"model\":\"minecraft:block/grass_block\"}}}"));
        files.put("assets/minecraft/models/block/grass_block.json", utf8("""
                {
                  "textures": {
                    "bottom": "minecraft:block/dirt",
                    "top": "minecraft:block/grass_block_top",
                    "side": "minecraft:block/grass_block_side",
                    "overlay": "minecraft:block/grass_block_side_overlay"
                  },
                  "elements": [
                    {
                      "from": [0, 0, 0], "to": [16, 16, 16],
                      "faces": {
                        "down":  {"texture": "#bottom", "cullface": "down"},
                        "up":    {"texture": "#top",    "cullface": "up",    "tintindex": 0},
                        "north": {"texture": "#side",   "cullface": "north"},
                        "south": {"texture": "#side",   "cullface": "south"},
                        "west":  {"texture": "#side",   "cullface": "west"},
                        "east":  {"texture": "#side",   "cullface": "east"}
                      }
                    },
                    {
                      "from": [0, 0, 0], "to": [16, 16, 16],
                      "faces": {
                        "north": {"texture": "#overlay", "cullface": "north", "tintindex": 0},
                        "south": {"texture": "#overlay", "cullface": "south", "tintindex": 0},
                        "west":  {"texture": "#overlay", "cullface": "west",  "tintindex": 0},
                        "east":  {"texture": "#overlay", "cullface": "east",  "tintindex": 0}
                      }
                    }
                  ]
                }
                """));

        files.put("assets/minecraft/blockstates/oak_leaves.json",
                utf8("{\"variants\":{\"\":{\"model\":\"minecraft:block/oak_leaves\"}}}"));
        files.put("assets/minecraft/models/block/oak_leaves.json", utf8("""
                {
                  "textures": {"all": "minecraft:block/oak_leaves"},
                  "elements": [{
                    "from": [0, 0, 0], "to": [16, 16, 16],
                    "faces": {
                      "down":  {"texture": "#all", "cullface": "down",  "tintindex": 0},
                      "up":    {"texture": "#all", "cullface": "up",    "tintindex": 0},
                      "north": {"texture": "#all", "cullface": "north", "tintindex": 0},
                      "south": {"texture": "#all", "cullface": "south", "tintindex": 0},
                      "west":  {"texture": "#all", "cullface": "west",  "tintindex": 0},
                      "east":  {"texture": "#all", "cullface": "east",  "tintindex": 0}
                    }
                  }]
                }
                """));

        files.put("assets/minecraft/blockstates/short_grass.json",
                utf8("{\"variants\":{\"\":{\"model\":\"minecraft:block/short_grass\"}}}"));
        files.put("assets/minecraft/models/block/short_grass.json", utf8("""
                {
                  "textures": {"cross": "minecraft:block/short_grass"},
                  "elements": [{
                    "from": [0.8, 0, 8], "to": [15.2, 16, 8],
                    "faces": {
                      "north": {"texture": "#cross", "tintindex": 0},
                      "south": {"texture": "#cross", "tintindex": 0}
                    }
                  }]
                }
                """));

        files.put("assets/minecraft/blockstates/water.json",
                utf8("{\"variants\":{\"\":{\"model\":\"minecraft:block/water\"}}}"));
        files.put("assets/minecraft/models/block/water.json",
                utf8("{\"textures\":{\"particle\":\"minecraft:block/water_still\"}}"));

        for (int i = 0; i < BIOME_COUNT; i++) {
            String json = String.format(Locale.ROOT,
                    "{\"temperature\":0.8,\"downfall\":0.4,\"effects\":{\"grass_color\":%d,\"foliage_color\":%d,\"water_color\":%d}}",
                    grassRgb(i), foliageRgb(i), waterRgb(i));
            files.put("data/fixture/worldgen/biome/biome_" + i + ".json", utf8(json));
        }

        return new BlockResourceSource() {
            @Override public Optional<byte[]> read(ResourceLocation.Kind kind, ResourceLocation id) {
                return Optional.ofNullable(files.get(id.entryName(kind)));
            }
            @Override public Optional<String> readJson(ResourceLocation.Kind kind, ResourceLocation id) {
                return read(kind, id).map(b -> new String(b, StandardCharsets.UTF_8));
            }
            @Override public ClientResourceArchive.Fingerprint fingerprint() {
                return new ClientResourceArchive.Fingerprint(Path.of("synthetic-600-biomes"), 1, 1, "v080-scale-test");
            }
            @Override public Optional<String> baseClientVersion() { return Optional.of("1.16.5"); }
            @Override public int baseResourceFormat() { return 6; }
            @Override public Optional<Long> worldSeed() { return Optional.of(0L); }
            @Override public Optional<Integer> biomeBlendRadius() { return Optional.of(0); }
            @Override public Optional<DimensionHeight> dimensionHeight(ResourceLocation dimension) {
                return Optional.of(new DimensionHeight(0, 256));
            }
        };
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] solidPng(int argb) throws IOException { return solidPng(4, 4, argb); }

    private static byte[] solidPng(int w, int h, int argb) throws IOException {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        Arrays.fill(row, argb);
        for (int y = 0; y < h; y++) img.setRGB(0, y, w, 1, row, 0, w);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] halfTransparentPng(int argb) throws IOException {
        var img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, y < 2 ? argb : 0x00000000);
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
