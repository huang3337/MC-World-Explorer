package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.viewer.*;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.mesh.*;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, read-only witnesses. The fixture selects existing resources, never launches game code. */
class UnboundTintResourceIntegrationTest {
    private static JsonObject fixture() throws Exception {
        String path = System.getenv("MCWE_R1_TINT_FIXTURE");
        assumeTrue(path != null, "需要显式选择只读真实资源与保存样本");
        return JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
    }
    private static Path path(JsonObject fixture, String key) { return Path.of(fixture.get(key).getAsString()); }
    private static BlockState state(String id, String... properties) {
        Map<String, String> values = new TreeMap<>();
        for (int i = 0; i < properties.length; i += 2) values.put(properties[i], properties[i + 1]);
        return new BlockState(id, values);
    }
    private static ModelRuleRegistry core() { return ModelRuleRegistry.defaults(); }
    private static void report(String file, Object value) throws Exception {
        String output = System.getProperty("mcwe.r1.output");
        if (output != null) {
            Path directory = Path.of(output); Files.createDirectories(directory);
            Files.writeString(directory.resolve(file + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(value));
        }
    }
    private static Map<String, Object> leafWitness(BlockResourceSource source, BlockState leaf, String expectedOrigin) throws Exception {
        String id = leaf.name();
        var prepared = BlockTextureSet.load(source, List.of(leaf), CancellationSignal.NONE, core());
        var faces = prepared.surfaces(leaf);
        assertNotNull(faces, prepared.fallbackReason(leaf)); assertEquals(6, faces.size());
        assertTrue(faces.stream().allMatch(f -> f.layer() == RenderLayer.CUTOUT));
        var texture = ResourceLocation.parse(id.replace(":", ":block/"));
        var raw = TextureAtlas.decodeResource(source.read(ResourceLocation.Kind.TEXTURE, texture).orElseThrow(),
                source.readJson(ResourceLocation.Kind.TEXTURE_META, texture));
        int[] atlas = prepared.atlas().pixels();
        for (var face : faces) {
            int x0 = Math.round(Collections.min(List.of(face.uv().get(0), face.uv().get(2), face.uv().get(4), face.uv().get(6))) * prepared.atlas().width());
            int y0 = Math.round(Collections.min(List.of(face.uv().get(1), face.uv().get(3), face.uv().get(5), face.uv().get(7))) * prepared.atlas().height());
            for (int y = 0; y < raw.getHeight(); y++) for (int x = 0; x < raw.getWidth(); x++)
                assertEquals(raw.getRGB(x, y), atlas[(y0 + y) * prepared.atlas().width() + x0 + x], "未绑定颜色必须保留全部原始 ARGB 像素");
        }
        assertTrue(prepared.notes(leaf).stream().anyMatch(n -> n.contains("颜色未解析") && n.contains("tintindex [0]") && n.contains(expectedOrigin)), prepared.notes(leaf).toString());
        var again = BlockTextureSet.load(source, List.of(leaf), CancellationSignal.NONE, core());
        assertEquals(prepared.notes(leaf), again.notes(leaf)); assertEquals(faces, again.surfaces(leaf));
        assertArrayEquals(atlas, again.atlas().pixels());
        return Map.of("rawId", id, "geometryReady", true, "colorStatus", "UNRESOLVED_TINT", "faces", faces.size(),
                "rawArgbPixelsCompared", raw.getWidth() * raw.getHeight() * faces.size(), "notes", prepared.notes(leaf),
                "textureOrigin", source.origin(ResourceLocation.Kind.TEXTURE, texture).orElseThrow(), "ruleFingerprint", core().fingerprint());
    }

    @Test void vanillaAndIndependentBopLeafUseTheSameCorePathWithoutGuessingHandlers() throws Exception {
        var fixture = fixture(); var witnesses = new ArrayList<Object>();
        try (var vanilla = ClientResourceArchive.open(path(fixture, "vanillaClient"), "1.21.1")) {
            witnesses.add(leafWitness(vanilla, state("minecraft:oak_leaves", "distance", "1", "persistent", "false", "waterlogged", "false"), "1.21.1.jar"));
            var grass = state("minecraft:grass_block", "snowy", "false");
            var wire = state("minecraft:redstone_wire", "power", "15", "north", "side", "south", "side", "east", "none", "west", "none");
            var textures = BlockTextureSet.load(vanilla, List.of(grass, wire), CancellationSignal.NONE, core());
            for (var block : List.of(grass, wire)) {
                assertNotNull(textures.surfaces(block), textures.fallbackReason(block));
                boolean requiresPosition=block.equals(grass);
                assertEquals(requiresPosition,textures.notes(block).stream().anyMatch(n -> n.contains("颜色未解析")));
                witnesses.add(Map.of("rawId", block.name(), "geometryReady", true, "colorStatus", requiresPosition?"UNRESOLVED_TINT":"BOUND_STATE_COLOR", "notes", textures.notes(block)));
            }
            vanilla.validate();
        }
        try (var instance = InstanceResourceSource.open(path(fixture, "dailyWorld"), path(fixture, "dailyClient"), "1.21.1", null, CancellationSignal.NONE, ignored -> {})) {
            witnesses.add(leafWitness(instance, state("biomesoplenty:willow_leaves", "distance", "1", "persistent", "false", "waterlogged", "false", "mossy", "false"), "BiomesOPlenty"));
            var stock = state("create:stock_link", "face", "floor", "facing", "west", "powered", "false", "waterlogged", "false");
            var rejected = BlockTextureSet.load(instance, List.of(stock), CancellationSignal.NONE, core());
            assertNull(rejected.surfaces(stock));
            assertTrue(rejected.fallbackReason(stock).contains("neoforge:composite"), rejected.fallbackReason(stock));
            // Composite is not admitted in the core; the former nested shade rejection is now unreachable here.
            witnesses.add(Map.of("rawId", stock.name(), "geometryReady", false, "reason", rejected.fallbackReason(stock)));
            instance.validateAll();
        }
        report("real-resource-witnesses", witnesses);
    }

    @Test void realSavedGrassAndLeavesKeepTheirIdentityAndCachedBindingDiagnostics() throws Exception {
        var fixture = fixture(); Path world = path(fixture, "smallWorld");
        var area = new VoxelAreaLoader().load(new VoxelAreaRequest(world, "minecraft:overworld", ChunkRectangle.target(0, 0, 0, 0)), CancellationSignal.NONE);
        var chunk = area.result(new ChunkCoordinate(0, 0)).optionalChunk().orElseThrow();
        var grass = chunk.blockState(9, 62, 0); var leaf = chunk.blockState(11, 64, 1);
        assertEquals("minecraft:grass_block", grass.name()); assertEquals("minecraft:oak_leaves", leaf.name());
        try (var source = InstanceResourceSource.open(world, path(fixture, "vanillaClient"), "1.21.1", null, CancellationSignal.NONE, ignored -> {})) {
            var textures = BlockTextureSet.load(source, area, CancellationSignal.NONE, core());
            var snapshot = new VoxelSceneBuilder(textures).build(area, CancellationSignal.NONE).snapshot().orElseThrow();
            for (var block : List.of(grass, leaf)) {
                assertNotNull(textures.surfaces(block), textures.fallbackReason(block));
                assertTrue(snapshot.warnings().stream().anyMatch(w -> w.code() == MeshWarning.Code.MODEL_APPROXIMATION && w.detail().contains(block.name()) && w.detail().contains("标准颜色绑定") && w.occurrences() > 0));
            }
            var key = new ViewerAreaKey(world, area.dimension().id(), area.target(), VoxelPreviewCoordinator.RENDER_VERSION);
            var signature = new RegionSourceSignature(List.of()); var cache = new ViewerSceneCache(ViewerPerformanceProfile.BASIC);
            assertTrue(cache.put(key, snapshot, signature));
            assertEquals(snapshot.warnings(), cache.get(key, signature).orElseThrow().warnings()); cache.clear();
            source.validateAll();
            report("saved-witnesses", Map.of("world", world.toString(), "chunks", "0,0", "scope", "真实保存数据与场景缓存；未验收原生窗口操作", "faces", snapshot.faceCount(),
                    "positions", List.of(Map.of("position", List.of(9, 62, 0), "state", grass), Map.of("position", List.of(11, 64, 1), "state", leaf)), "warnings", snapshot.warnings()));
        }
    }
}
