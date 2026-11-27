package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.ChunkNbtDecoder;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LegacyClientTintFallbackTest {
    @TempDir Path tempDir;

    @Test void synthetic1165EndToEndResolvesModelsBiomesAndVanillaFallbackTint() throws Exception {
        byte[] white16 = png(16, 16, 0xFFFFFFFF);
        byte[] grassMap = png(256, 256, 0xFF79C05A);
        byte[] foliageMap = png(256, 256, 0xFF59AE30);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("version.json", "{\"id\":\"1.16.5\",\"world_version\":2586,\"pack_version\":6}".getBytes(StandardCharsets.UTF_8));
        addBaseStone(entries, white16);
        addTintedBlock(entries, "grass_block", white16);
        addTintedBlock(entries, "grass", white16);
        addTintedBlock(entries, "oak_leaves", white16);
        entries.put("assets/minecraft/textures/colormap/grass.png", grassMap);
        entries.put("assets/minecraft/textures/colormap/foliage.png", foliageMap);

        Path jar = writeZip("client-1.16.5.jar", entries);
        try (var archive = ClientResourceArchive.open(jar, "1.16.5")) {
            assertEquals(6, archive.baseResourceFormat());
            var source = withWorldInputs(archive, 12345L, 0);

            int[] biomes1024 = new int[1024];
            Arrays.fill(biomes1024, 1); // plains
            // Section 4, all quart cells in section 4 set to warm_ocean (44) at qx=3
            for (int qy = 16; qy < 20; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    biomes1024[((qy << 2) | qz) << 2 | 3] = 44;
                }
            }

            long[] blockStates = new long[256];
            // (0,0,0)=grass_block(0), (1,0,0)=grass(1), (2,0,0)=oak_leaves(2)
            blockStates[0] = (0L) | (1L << 4) | (2L << 8);
            CompoundBinaryTag sec4 = CompoundBinaryTag.builder()
                    .putByte("Y", (byte) 4)
                    .put("Palette", palette("minecraft:grass_block", "minecraft:grass", "minecraft:oak_leaves"))
                    .putLongArray("BlockStates", blockStates)
                    .build();
            VoxelChunk chunk = decodeLegacyChunk(2586, 0, 0, sec4, biomes1024, null);
            VoxelAreaLoadResult area = singleChunkArea(chunk, WorldDimension.overworld(tempDir));

            var prepared = BlockTextureSet.load(source, area, CancellationSignal.NONE);
            BlockState grassBlock = chunk.blockState(0, 64, 0);
            BlockState shortGrassOld = chunk.blockState(1, 64, 0);
            BlockState oakLeaves = chunk.blockState(2, 64, 0);

            BlockState renderedGrass = prepared.renderState(grassBlock, 0, 64, 0);
            BlockState renderedOldGrass = prepared.renderState(shortGrassOld, 1, 64, 0);
            BlockState renderedLeaves = prepared.renderState(oakLeaves, 2, 64, 0);

            assertFalse(renderedGrass.properties().containsKey("__mcwe_tint"));
            assertFalse(renderedOldGrass.properties().containsKey("__mcwe_tint"));
            assertFalse(renderedLeaves.properties().containsKey("__mcwe_tint"));
            assertEquals(0x79C05A, prepared.surfaceColor(prepared.surfaces(renderedGrass).getFirst(), 0, 64, 0));
            assertEquals(0x79C05A, prepared.surfaceColor(prepared.surfaces(renderedOldGrass).getFirst(), 1, 64, 0));
            assertEquals(0x59AE30, prepared.surfaceColor(prepared.surfaces(renderedLeaves).getFirst(), 2, 64, 0));

            var notes = prepared.notes(renderedGrass);
            assertTrue(notes.stream().anyMatch(n -> n.contains("通用近似") && n.contains("未经该版本客户端字节码静态注册核对")), notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("维度高度取自原版标准默认范围")), notes.toString());
            assertTrue(notes.stream().anyMatch(n -> n.contains("群系气候与水色取自内置原版表近似")), notes.toString());

            // Dry foliage (1.21.5+) must not be bound for pack_format 6 (< 35), while waterBlocks repeats WATER
            var colors = new ClientBlockColors(source);
            assertTrue(colors.layer(ResourceLocation.parse("minecraft:leaf_litter"), 0).isEmpty());
            assertEquals(Optional.of(ClientBlockColors.Kind.GRASS), colors.layer(ResourceLocation.parse("minecraft:grass"), 0));
            assertEquals(Optional.of(ClientBlockColors.Kind.WATER), colors.layer(ResourceLocation.parse("minecraft:water"), 0));
            assertEquals(Optional.of(ClientBlockColors.Kind.WATER), colors.layer(ResourceLocation.parse("minecraft:water"), 1));

            var modernUnregistered = new BlockResourceSource() {
                @Override public Optional<String> baseClientVersion() { return Optional.of("26.2"); }
                @Override public int baseResourceFormat() { return 90; }
                @Override public Optional<byte[]> read(ResourceLocation.Kind kind, ResourceLocation id) { return Optional.empty(); }
                @Override public Optional<String> readJson(ResourceLocation.Kind kind, ResourceLocation id) { return Optional.empty(); }
                @Override public ClientResourceArchive.Fingerprint fingerprint() {
                    return new ClientResourceArchive.Fingerprint(Path.of("fixture-26.2"), 1, 1, "26.2");
                }
            };
            var modernColors = new ClientBlockColors(modernUnregistered);
            assertEquals(Optional.of(ClientBlockColors.Kind.WHITE), modernColors.layer(ResourceLocation.parse("minecraft:water"), 0));
            assertTrue(modernColors.layer(ResourceLocation.parse("minecraft:water"), 1).isEmpty());
            assertEquals(Optional.of(ClientBlockColors.Kind.DRY_FOLIAGE), modernColors.layer(ResourceLocation.parse("minecraft:leaf_litter"), 0));

            // Warm ocean water color (0x43D5EE) via SavedBiomeColors
            var waterModel = new BlockModelResolver(source).resolve(grassBlock).model().orElseThrow();
            var resolver = new BlockTintResolver(source);
            var warmWaterPos = new ModelBlockContext(area, new ModelBlockContext.Position(14, 64, 2), CancellationSignal.NONE);
            var waterFace = waterModel.faces().getFirst();
            var syntheticWaterModel = new BlockModelResolver.Model(
                    waterModel.id(), 0, 0, false,
                    List.of(new BlockModelResolver.Face(
                            waterFace.direction(), waterFace.texture(), waterFace.uv(), 0,
                            BlockTintResolver.FLUID_WATER, waterFace.cullFace(), 0,
                            waterFace.from(), waterFace.to(), waterFace.elementRotation(), waterFace.shade(),
                            waterFace.vertices(), waterFace.vertexUv(),
                            waterFace.opacity(), waterFace.material())),
                    false, List.of());
            var waterTint = resolver.colors(new BlockState("minecraft:water", Map.of()), syntheticWaterModel, warmWaterPos);
            assertEquals(0x1000000 | 0x43D5EE, waterTint.values().get(BlockTintResolver.FLUID_WATER));
        }
    }

    @Test void synthetic1132EndToEndResolvesPackMcmetaAndInt256Biomes() throws Exception {
        byte[] white16 = png(16, 16, 0xFFFFFFFF);
        byte[] grassMap = png(256, 256, 0xFF66AA44);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", "{\"pack\":{\"pack_format\":4,\"description\":\"1.13.2\"}}".getBytes(StandardCharsets.UTF_8));
        addBaseStone(entries, white16);
        addTintedBlock(entries, "grass_block", white16);
        addTintedBlock(entries, "grass", white16);
        entries.put("assets/minecraft/textures/colormap/grass.png", grassMap);

        Path jar = writeZip("client-1.13.2.jar", entries);
        try (var archive = ClientResourceArchive.open(jar, "1.13.2")) {
            assertEquals(4, archive.baseResourceFormat());
            var source = withWorldInputs(archive, 42L, 0);

            int[] biomes256 = new int[256];
            Arrays.fill(biomes256, 4); // forest
            CompoundBinaryTag sec4 = CompoundBinaryTag.builder()
                    .putByte("Y", (byte) 4)
                    .put("Palette", palette("minecraft:grass_block"))
                    .putLongArray("BlockStates", new long[0])
                    .build();
            VoxelChunk chunk = decodeLegacyChunk(1631, 0, 0, sec4, biomes256, null);
            VoxelAreaLoadResult area = singleChunkArea(chunk, WorldDimension.overworld(tempDir));

            var prepared = BlockTextureSet.load(source, area, CancellationSignal.NONE);
            BlockState grass = chunk.blockState(0, 64, 0);
            BlockState rendered = prepared.renderState(grass, 0, 64, 0);
            assertFalse(rendered.properties().containsKey("__mcwe_tint"));
            assertEquals(0x66AA44, prepared.surfaceColor(prepared.surfaces(rendered).getFirst(), 0, 64, 0));
        }
    }

    @Test void synthetic1182FallbackUsesNegative64To320OverworldHeight() throws Exception {
        byte[] white16 = png(16, 16, 0xFFFFFFFF);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("version.json", "{\"id\":\"1.18.2\",\"world_version\":2975,\"pack_version\":{\"resource\":8}}".getBytes(StandardCharsets.UTF_8));
        addBaseStone(entries, white16);
        Path jar = writeZip("client-1.18.2.jar", entries);
        try (var archive = ClientResourceArchive.open(jar, "1.18.2")) {
            var source = withWorldInputs(archive, 99L, 0);
            var overworld = SavedBiomeColors.inputs(source, "minecraft:overworld");
            assertTrue(overworld.fallbackDimensionHeight());
            assertEquals(new BlockResourceSource.DimensionHeight(-64, 384), overworld.height());
            var nether = SavedBiomeColors.inputs(source, "minecraft:the_nether");
            assertEquals(new BlockResourceSource.DimensionHeight(0, 256), nether.height());
        }
    }

    @Test void registeredVersionsNeverFallbackWhenClassesAreMissingOrTampered() throws Exception {
        for (String registered : List.of("1.20.1", "1.21.1", "1.21.5", "26.1", "26.3")) {
            BlockResourceSource tampered = new BlockResourceSource() {
                @Override public Optional<String> baseClientVersion() { return Optional.of(registered); }
                @Override public int baseResourceFormat() { return 34; }
                @Override public Optional<RegistrationClass> clientClass(String internalName) {
                    return Optional.of(new RegistrationClass(internalName, new byte[]{1, 2, 3}, "fixture", "tampered"));
                }
                @Override public Optional<byte[]> read(ResourceLocation.Kind kind, ResourceLocation id) { return Optional.empty(); }
                @Override public Optional<String> readJson(ResourceLocation.Kind kind, ResourceLocation id) { return Optional.empty(); }
                @Override public ClientResourceArchive.Fingerprint fingerprint() {
                    return new ClientResourceArchive.Fingerprint(Path.of("fixture"), 1, 1, "tampered-" + registered);
                }
            };
            assertThrows(ModelRule.Failure.class, () -> new ClientBlockColors(tampered), registered);
        }
    }

    @Test void modernClientWithBundledBiomeRegistryDoesNotFallbackWhenBiomeJsonIsMissing() throws Exception {
        byte[] white16 = png(16, 16, 0xFFFFFFFF);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("version.json", "{\"id\":\"1.20.4\",\"world_version\":3700,\"pack_version\":{\"resource\":22}}".getBytes(StandardCharsets.UTF_8));
        addBaseStone(entries, white16);
        entries.put("data/minecraft/worldgen/biome/plains.json", "{\"temperature\":0.8,\"downfall\":0.4,\"effects\":{\"water_color\":4159204}}".getBytes(StandardCharsets.UTF_8));
        entries.put("data/minecraft/dimension_type/overworld.json", "{\"min_y\":-64,\"height\":384}".getBytes(StandardCharsets.UTF_8));
        Path jar = writeZip("client-1.20.4.jar", entries);
        try (var archive = ClientResourceArchive.open(jar, "1.20.4")) {
            var source = withWorldInputs(archive, 1L, 0);
            // dimension_type is bundled -> missing dimensionHeight must NOT fall back
            assertThrows(ModelRule.Failure.class, () -> SavedBiomeColors.inputs(source, "minecraft:overworld"));
            // worldgen/biome is bundled -> missing biome JSON must NOT fall back to VanillaLegacyBiomes
            var biomes = new SavedBiomeColors();
            var pos = new ModelBlockContext.Position(0, 64, 0);
            var center = new ModelBlockContext.Sample(Optional.of(BlockState.AIR), Optional.empty(),
                    com.mcworldexplorer.voxel.io.ChunkLoadStatus.SUCCESS, "");
            var view = new ModelBlockContext.View(pos, "minecraft:overworld", Map.of(new ModelBlockContext.Offset(0, 0, 0), center));
            Map<ModelBlockContext.Position, String> cells = new HashMap<>();
            for (int x = -1; x <= 1; x++) for (int y = 15; y <= 17; y++) for (int z = -1; z <= 1; z++) {
                cells.put(new ModelBlockContext.Position(x, y, z), "minecraft:forest");
            }
            var inputs = new SavedBiomeColors.Inputs("1.20.4", 0L, 0, new BlockResourceSource.DimensionHeight(-64, 384));
            assertThrows(ModelRule.Failure.class, () -> biomes.color(
                    ModelRule.BiomeColor.GRASS, source, id -> Optional.empty(), n -> {},
                    new ModelBlockContext(view, cells), pos, new java.util.ArrayList<>(), inputs));
        }
    }

    private static BlockResourceSource withWorldInputs(ClientResourceArchive delegate, long seed, int blendRadius) {
        return new BlockResourceSource() {
            @Override public Optional<byte[]> read(ResourceLocation.Kind kind, ResourceLocation id) throws IOException { return delegate.read(kind, id); }
            @Override public boolean hasBaseTexture(ResourceLocation id) throws IOException { return delegate.hasBaseTexture(id); }
            @Override public Optional<String> readJson(ResourceLocation.Kind kind, ResourceLocation id) throws IOException { return delegate.readJson(kind, id); }
            @Override public ClientResourceArchive.Fingerprint fingerprint() { return delegate.fingerprint(); }
            @Override public Optional<String> origin(ResourceLocation.Kind kind, ResourceLocation id) { return delegate.origin(kind, id); }
            @Override public Optional<Boolean> baseDataRegistry(String directory) throws IOException { return delegate.baseDataRegistry(directory); }
            @Override public Optional<String> baseClientVersion() { return delegate.baseClientVersion(); }
            @Override public int baseResourceFormat() { return delegate.baseResourceFormat(); }
            @Override public Optional<Long> worldSeed() { return Optional.of(seed); }
            @Override public Optional<Integer> biomeBlendRadius() { return Optional.of(blendRadius); }
            @Override public Optional<RegistrationClass> clientClass(String internalName) throws IOException { return delegate.clientClass(internalName); }
            @Override public void validate() throws IOException { delegate.validate(); }
        };
    }

    private static void addBaseStone(Map<String, byte[]> entries, byte[] tex) {
        entries.put("assets/minecraft/blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/models/block/stone.json",
                "{\"textures\":{\"all\":\"minecraft:block/stone\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#all\"}}}]}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/textures/block/stone.png", tex);
    }

    private static void addTintedBlock(Map<String, byte[]> entries, String name, byte[] tex) {
        entries.put("assets/minecraft/blockstates/" + name + ".json",
                ("{\"variants\":{\"\":{\"model\":\"minecraft:block/" + name + "\"}}}").getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/models/block/" + name + ".json",
                ("{\"textures\":{\"all\":\"minecraft:block/" + name + "\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#all\",\"tintindex\":0}}}]}").getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/textures/block/" + name + ".png", tex);
    }

    private static byte[] png(int width, int height, int argb) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        Arrays.fill(row, argb);
        for (int y = 0; y < height; y++) img.setRGB(0, y, width, 1, row, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private Path writeZip(String name, Map<String, byte[]> entries) throws IOException {
        Path file = tempDir.resolve(name);
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static ListBinaryTag palette(String... names) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (String name : names) {
            builder.add(CompoundBinaryTag.builder().putString("Name", name).build());
        }
        return builder.build();
    }

    private static VoxelChunk decodeLegacyChunk(int dataVersion, int cx, int cz, CompoundBinaryTag section,
                                                int[] intBiomes, byte[] byteBiomes) throws Exception {
        CompoundBinaryTag.Builder level = CompoundBinaryTag.builder()
                .putInt("xPos", cx).putInt("zPos", cz)
                .put("Sections", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(section)));
        if (intBiomes != null) level.putIntArray("Biomes", intBiomes);
        if (byteBiomes != null) level.putByteArray("Biomes", byteBiomes);
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .putInt("DataVersion", dataVersion)
                .put("Level", level.build())
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(root, out, BinaryTagIO.Compression.NONE);
        return ChunkNbtDecoder.read(new ByteArrayInputStream(out.toByteArray()), cx, cz).chunk();
    }

    private VoxelAreaLoadResult singleChunkArea(VoxelChunk chunk, WorldDimension dimension) throws Exception {
        ChunkCoordinate center = new ChunkCoordinate(chunk.chunkX(), chunk.chunkZ());
        ChunkRectangle target = ChunkRectangle.target(center.x(), center.x(), center.z(), center.z());
        Map<ChunkCoordinate, ChunkLoadResult> chunks = new HashMap<>();
        for (ChunkCoordinate coord : target.expanded(1).coordinates()) {
            if (coord.equals(center)) {
                chunks.put(coord, ChunkLoadResult.success(coord, chunk, 0, 0));
            } else {
                var neighborBiomes = new HashMap<Integer, com.mcworldexplorer.voxel.data.BiomeSection>();
                for (int y = 0; y < 16; y++) {
                    int sectionY = y;
                    chunk.biomeSection(sectionY).ifPresent(b -> neighborBiomes.put(sectionY, b));
                }
                VoxelChunk neighbor = new VoxelChunk(coord.x(), coord.z(), List.of(), List.of(), neighborBiomes);
                chunks.put(coord, ChunkLoadResult.success(coord, neighbor, 0, 0));
            }
        }
        return new VoxelAreaLoadResult(tempDir, dimension, target, chunks);
    }
}
