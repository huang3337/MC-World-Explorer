package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.nbt.LevelDatReader;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Reason;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end synthetic tests for pre-flattening 3D texture pipelines:
 * Tier 2 (1.8–1.12.2 LEGACY_JSON_MODEL + Forge Blockstate V1) and
 * Tier 3 (1.6.1–1.7.10 LEGACY_FLAT_TEXTURE + mods/<mcversion> + mcmod.info).
 */
class PreFlatteningResourcePipelineTest {
    @TempDir Path tempDir;

    @Test
    void opensLegacy1122ArchiveMapsVanillaStatesAndParsesForgeBlockstateV1() throws Exception {
        byte[] png = solidPng(0xFF808080);
        byte[] redPng = solidPng(0xFFD03030);
        byte[] bluePng = solidPng(0xFF3060D0);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pack.mcmeta", "{\"pack\":{\"pack_format\":3,\"description\":\"1.12.2\"}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/blockstates/stone.json",
                "{\"variants\":{\"normal\":{\"model\":\"stone\"}}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/models/block/cube_all.json",
                ("{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                        + "\"down\":{\"texture\":\"#all\"},\"up\":{\"texture\":\"#all\"},"
                        + "\"north\":{\"texture\":\"#all\"},\"south\":{\"texture\":\"#all\"},"
                        + "\"west\":{\"texture\":\"#all\"},\"east\":{\"texture\":\"#all\"}}}]}").getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/models/block/stone.json",
                "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"blocks/stone\"}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/textures/blocks/stone.png", png);

        // Log with axis=x (from data=4)
        entries.put("assets/minecraft/blockstates/oak_log.json",
                "{\"variants\":{\"axis=y\":{\"model\":\"oak_log\"},\"axis=x\":{\"model\":\"oak_log\",\"x\":90,\"y\":90}}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/models/block/oak_log.json",
                "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"blocks/log_oak\"}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/textures/blocks/log_oak.png", png);

        // Silver wool (mapped from light_gray_wool)
        entries.put("assets/minecraft/blockstates/silver_wool.json",
                "{\"variants\":{\"normal\":{\"model\":\"silver_wool\"}}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/models/block/silver_wool.json",
                "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"blocks/wool_colored_silver\"}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/textures/blocks/wool_colored_silver.png", png);

        // Stairs (cobblestone_stairs -> stone_stairs, data=6 -> facing=south, half=top, shape=straight)
        entries.put("assets/minecraft/blockstates/stone_stairs.json",
                "{\"variants\":{\"facing=south,half=top,shape=straight\":{\"model\":\"stone\",\"x\":180,\"y\":90}}}".getBytes(StandardCharsets.UTF_8));

        // Polished granite -> smooth_granite, double tall_grass -> double_grass, sapling -> stage=1, water -> builtin fluid
        entries.put("assets/minecraft/blockstates/smooth_granite.json",
                "{\"variants\":{\"normal\":{\"model\":\"stone\"}}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/blockstates/double_grass.json",
                "{\"variants\":{\"half=lower\":{\"model\":\"stone\"},\"half=upper\":{\"model\":\"stone\",\"y\":90}}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/blockstates/oak_sapling.json",
                "{\"variants\":{\"stage=0\":{\"model\":\"stone\"},\"stage=1\":{\"model\":\"stone\",\"y\":180}}}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/textures/blocks/water_still.png", bluePng);
        entries.put("assets/minecraft/textures/blocks/water_flow.png", bluePng);

        // Forge Blockstate V1 with defaults, Cartesian property map, submodel, and uvlock
        entries.put("assets/testmod/blockstates/forge_machine.json",
                ("{\"forge_marker\":1,"
                        + "\"defaults\":{\"model\":\"minecraft:cube_all\",\"textures\":{\"all\":\"testmod:blocks/machine_base\"},\"transform\":\"forge:default-block\"},"
                        + "\"variants\":{\"facing\":{\"north\":{},\"east\":{\"y\":90,\"uvlock\":true,\"submodel\":{\"cap\":{\"model\":\"minecraft:cube_all\",\"textures\":{\"all\":\"testmod:blocks/machine_cap\"}}}}}}}")
                        .getBytes(StandardCharsets.UTF_8));
        entries.put("assets/testmod/textures/blocks/machine_base.png", redPng);
        entries.put("assets/testmod/textures/blocks/machine_cap.png", bluePng);

        // Forge Blockstate V1 requiring unknown runtime property
        entries.put("assets/testmod/blockstates/runtime_block.json",
                ("{\"forge_marker\":1,\"defaults\":{\"model\":\"minecraft:cube_all\"},"
                        + "\"variants\":{\"power\":{\"low\":{},\"high\":{\"y\":180}}}}")
                        .getBytes(StandardCharsets.UTF_8));

        Path instance = tempDir.resolve("instances/1.12.2");
        Path world = instance.resolve("saves/sample");
        Files.createDirectories(world);
        Path jar = writeZip(instance.resolve("1.12.2.jar"), entries);

        var located = new ClientResourceLocator().locate(world, "1.12.2", Optional.empty(), List.of(), CancellationSignal.NONE);
        assertEquals(ClientResourceLocator.Status.FOUND, located.status());
        assertEquals(jar.toRealPath(), located.path().orElseThrow());

        try (var archive = ClientResourceArchive.open(jar, "1.12.2")) {
            assertEquals(ClientResourceArchive.ProtocolMode.LEGACY_JSON_MODEL, archive.protocolMode());
            assertEquals(3, archive.baseResourceFormat());
            // Transparent plural <-> singular texture fallback
            assertTrue(archive.hasBaseTexture(ResourceLocation.parse("minecraft:block/stone")));
            assertTrue(archive.hasBaseTexture(ResourceLocation.parse("minecraft:blocks/stone")));

            var resolver = new BlockModelResolver(archive);
            var stone = resolver.resolve(new BlockState("minecraft:stone", Map.of("data", "0")));
            assertTrue(stone.resolved(), stone.detail());

            var logX = resolver.resolve(new BlockState("minecraft:oak_log", Map.of("data", "4")));
            assertTrue(logX.resolved(), logX.detail());
            assertEquals(90, logX.model().orElseThrow().xRotation());
            assertEquals(90, logX.model().orElseThrow().yRotation());

            var silverWool = resolver.resolve(new BlockState("minecraft:light_gray_wool", Map.of("data", "8")));
            assertTrue(silverWool.resolved(), silverWool.detail());

            var stairs = resolver.resolve(new BlockState("minecraft:cobblestone_stairs", Map.of("data", "6")));
            assertTrue(stairs.resolved(), stairs.detail());
            assertEquals(180, stairs.model().orElseThrow().xRotation());
            assertEquals(90, stairs.model().orElseThrow().yRotation());

            var granite = resolver.resolve(new BlockState("minecraft:polished_granite", Map.of("data", "2")));
            assertTrue(granite.resolved(), granite.detail());

            var doubleGrassUpper = resolver.resolve(new BlockState("minecraft:tall_grass", Map.of("data", "10")));
            assertTrue(doubleGrassUpper.resolved(), doubleGrassUpper.detail());
            assertEquals(90, doubleGrassUpper.model().orElseThrow().yRotation());

            var saplingStage1 = resolver.resolve(new BlockState("minecraft:oak_sapling", Map.of("data", "8")));
            assertTrue(saplingStage1.resolved(), saplingStage1.detail());
            assertEquals(180, saplingStage1.model().orElseThrow().yRotation());

            var legacyWater = resolver.resolve(new BlockState("minecraft:water", Map.of("data", "3")));
            assertTrue(legacyWater.resolved(), legacyWater.detail());
            assertFalse(legacyWater.model().orElseThrow().faces().isEmpty());
            assertEquals(5f / 9f, com.mcworldexplorer.voxel.data.SavedFluid.ownHeight(
                    new BlockState("minecraft:water", Map.of("data", "3"))), 1e-6f);

            var forgeEast = resolver.resolve(new BlockState("testmod:forge_machine", Map.of("facing", "east")));
            assertTrue(forgeEast.resolved(), forgeEast.detail());
            assertEquals(2, forgeEast.model().orElseThrow().parts().size());
            assertEquals(90, forgeEast.model().orElseThrow().yRotation());
            assertTrue(forgeEast.model().orElseThrow().uvLock());

            var runtimeFail = resolver.resolve(new BlockState("testmod:runtime_block", Map.of("data", "1")));
            assertFalse(runtimeFail.resolved());
            assertEquals(Reason.UNSUPPORTED_MODEL, runtimeFail.reason());
            assertTrue(runtimeFail.detail().contains("运行时属性"));

            var textureSet = BlockTextureSet.load(archive, List.of(
                    new BlockState("minecraft:stone", Map.of("data", "0")),
                    new BlockState("minecraft:oak_log", Map.of("data", "4")),
                    new BlockState("testmod:forge_machine", Map.of("facing", "east"))), CancellationSignal.NONE);
            assertNotNull(textureSet.surfaces(new BlockState("testmod:forge_machine", Map.of("facing", "east"))));
            assertTrue(textureSet.atlas().width() > 0);
        }
    }

    @Test
    void opensLegacy1710ArchiveScansVersionedModsAndSynthesizesFlatTextures() throws Exception {
        byte[] gray = solidPng(0xFF909090);
        byte[] green = solidPng(0xFF55AA44);
        byte[] water = solidPng(0xFF3366EE);
        byte[] colormap = colormapPng(0xFF66BB44);

        Map<String, byte[]> clientEntries = new LinkedHashMap<>();
        clientEntries.put("assets/minecraft/textures/blocks/stone.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/dirt.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/grass_top.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/grass_side.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/grass_side_overlay.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/log_oak.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/log_oak_top.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/leaves_oak.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/wool_colored_silver.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/hardened_clay_stained_red.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/glass_blue.png", water);
        clientEntries.put("assets/minecraft/textures/blocks/tallgrass.png", green);
        clientEntries.put("assets/minecraft/textures/blocks/waterlily.png", green);
        clientEntries.put("assets/minecraft/textures/blocks/vine.png", green);
        clientEntries.put("assets/minecraft/textures/blocks/torch_on.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/ladder.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/rail_normal.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/water_still.png", water);
        clientEntries.put("assets/minecraft/textures/blocks/water_flow.png", water);
        clientEntries.put("assets/minecraft/textures/blocks/stone_slab_top.png", gray);
        clientEntries.put("assets/minecraft/textures/blocks/stone_slab_side.png", gray);
        clientEntries.put("assets/minecraft/textures/colormap/grass.png", colormap);
        clientEntries.put("assets/minecraft/textures/colormap/foliage.png", colormap);

        Path instance = tempDir.resolve("1.7.10-Forge");
        Path world = instance.resolve("saves/test_world");
        Files.createDirectories(world);
        Path clientJar = writeZip(instance.resolve("1.7.10-Forge.jar"), clientEntries);

        // Mod in mods/1.7.10/*.jar with array mcmod.info and a single same-named block texture
        Map<String, byte[]> modEntries = new LinkedHashMap<>();
        modEntries.put("mcmod.info", "[{\"modid\":\"SimpleMod\",\"name\":\"Simple Mod\"}]".getBytes(StandardCharsets.UTF_8));
        modEntries.put("assets/simplemod/textures/blocks/ruby_block.png", solidPng(0xFFCC2244));
        writeZip(instance.resolve("mods/1.7.10/simplemod-1.0.jar"), modEntries);

        // Mod in mods/*.jar with modList-wrapped mcmod.info plus an ambiguous multi-face machine block
        Map<String, byte[]> mod2Entries = new LinkedHashMap<>();
        mod2Entries.put("mcmod.info", "{\"modListVersion\":2,\"modList\":[{\"modid\":\"wrappedmod\"}]}".getBytes(StandardCharsets.UTF_8));
        mod2Entries.put("assets/wrappedmod/textures/blocks/copper_ore.png", solidPng(0xFFCC8844));
        mod2Entries.put("assets/wrappedmod/textures/blocks/crusher.png", solidPng(0xFF888888));
        mod2Entries.put("assets/wrappedmod/textures/blocks/crusher_front.png", solidPng(0xFFAA4444));
        writeZip(instance.resolve("mods/wrappedmod-1.0.jar"), mod2Entries);

        // Auto-locate for Anvil (<1.9) via <instance>/<instance>.jar
        var located = new ClientResourceLocator().locate(world, LevelDatReader.LEGACY_PRE_1_9_VERSION,
                Optional.empty(), List.of(), CancellationSignal.NONE);
        assertEquals(ClientResourceLocator.Status.FOUND, located.status());
        assertEquals(clientJar.toRealPath(), located.path().orElseThrow());

        try (var source = InstanceResourceSource.open(world, clientJar, LevelDatReader.LEGACY_PRE_1_9_VERSION,
                instance, CancellationSignal.NONE, msg -> {})) {
            assertEquals(ClientResourceArchive.ProtocolMode.LEGACY_FLAT_TEXTURE, source.baseProtocolMode());
            assertEquals(Optional.of(true), source.hasMod("simplemod"));
            assertEquals(Optional.of(true), source.hasMod("wrappedmod"));
            assertEquals(Optional.of("forge"), source.platform());

            List<BlockState> states = List.of(
                    new BlockState("minecraft:stone", Map.of("data", "0")),
                    new BlockState("minecraft:grass_block", Map.of("data", "0")),
                    new BlockState("minecraft:oak_log", Map.of("data", "4")),
                    new BlockState("minecraft:oak_leaves", Map.of("data", "0")),
                    new BlockState("minecraft:light_gray_wool", Map.of("data", "8")),
                    new BlockState("minecraft:red_terracotta", Map.of("data", "14")),
                    new BlockState("minecraft:blue_stained_glass", Map.of("data", "11")),
                    new BlockState("minecraft:smooth_stone_slab", Map.of("data", "8")),
                    new BlockState("minecraft:short_grass", Map.of("data", "1")),
                    new BlockState("minecraft:lily_pad", Map.of("data", "0")),
                    new BlockState("minecraft:vine", Map.of("data", "5")),
                    new BlockState("minecraft:torch", Map.of("data", "5")),
                    new BlockState("minecraft:ladder", Map.of("data", "3")),
                    new BlockState("minecraft:rail", Map.of("data", "0")),
                    new BlockState("minecraft:water", Map.of("data", "0")),
                    new BlockState("simplemod:ruby_block", Map.of("data", "0")),
                    new BlockState("wrappedmod:copper_ore", Map.of("data", "0")),
                    new BlockState("wrappedmod:crusher", Map.of("data", "0")),
                    new BlockState("Thaumcraft:blockWoodenDevice", Map.of("data", "0"))
            );

            var textureSet = BlockTextureSet.load(source, states, CancellationSignal.NONE);
            for (int i = 0; i < states.size() - 2; i++) {
                BlockState state = states.get(i);
                assertNotNull(textureSet.surfaces(state), "Expected baked surfaces for " + state + ": " + textureSet.fallbackReason(state));
                assertFalse(textureSet.surfaces(state).isEmpty(), "Expected non-empty faces for " + state);
            }
            // Simple mod block carries explicit approximation note
            assertFalse(textureSet.notes(new BlockState("simplemod:ruby_block", Map.of("data", "0"))).isEmpty());
            // Multi-face Mod block (with _front companion texture) and uppercase complex Mod block both degrade cleanly
            assertNull(textureSet.surfaces(new BlockState("wrappedmod:crusher", Map.of("data", "0"))));
            BlockState complexMod = states.getLast();
            assertNull(textureSet.surfaces(complexMod));
            assertTrue(textureSet.fallbackReason(complexMod).contains("1.6–1.7 平面纹理模式未覆盖"));
        }
    }

    private static Path writeZip(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private static byte[] solidPng(int argb) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] colormapPng(int argb) throws IOException {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                img.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
