package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.viewer.*;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.mesh.MeshWarning;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UnboundModelTintTest {
    private static final BlockState TINTED = state("tinted");
    private static BlockState state(String name) { return new BlockState("unlisted:" + name, Map.of()); }

    private static BlockResourceSource source(Map<String, String> models) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xFFFFFFFF);
        var png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("assets/unlisted/textures/block/paint.png", png.toByteArray());
        models.forEach((name, model) -> {
            entries.put("assets/unlisted/blockstates/" + name + ".json",
                    ("{\"variants\":{\"\":{\"model\":\"unlisted:block/" + name + "\"}}}").getBytes(StandardCharsets.UTF_8));
            entries.put("assets/unlisted/models/block/" + name + ".json", model.getBytes(StandardCharsets.UTF_8));
        });
        return new BlockResourceSource() {
            public Optional<byte[]> read(ResourceLocation.Kind kind, ResourceLocation id) { return Optional.ofNullable(entries.get(id.entryName(kind))); }
            public Optional<String> readJson(ResourceLocation.Kind kind, ResourceLocation id) { return read(kind, id).map(bytes -> new String(bytes, StandardCharsets.UTF_8)); }
            public ClientResourceArchive.Fingerprint fingerprint() { return new ClientResourceArchive.Fingerprint(Path.of("fixture"), 1, 1, "unbound-tint-test"); }
            public Optional<String> origin(ResourceLocation.Kind kind, ResourceLocation id) { return Optional.of("fixture-pack"); }
        };
    }

    private static String model(String faceExtra) {
        return "{\"textures\":{\"p\":\"unlisted:block/paint\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#p\"" + faceExtra + "}}}]}";
    }

    static int pixel(BlockTextureSet set, BlockState state) {
        var face = set.surfaces(state).getFirst();
        double u = 0, v = 0;
        for (int i = 0; i < 4; i++) { u += face.uv().get(i * 2) / 4; v += face.uv().get(i * 2 + 1) / 4; }
        return set.atlas().pixels()[(int) (v * set.atlas().height()) * set.atlas().width() + (int) (u * set.atlas().width())];
    }

    @Test void unboundIndicesKeepRawTextureAndGeometryWithSourceDiagnostic() throws Exception {
        for (int index : new int[]{0, 1, 255}) {
            var textures = BlockTextureSet.load(source(Map.of("tinted", model(",\"tintindex\":" + index))), List.of(TINTED), CancellationSignal.NONE);
            assertNotNull(textures.surfaces(TINTED));
            assertEquals(1, textures.surfaces(TINTED).size());
            assertEquals(0xFFFFFFFF, pixel(textures, TINTED), "未绑定的索引不能猜成草色");
            assertTrue(textures.notes(TINTED).stream().anyMatch(n -> n.contains("颜色未解析") && n.contains(Integer.toString(index)) && n.contains("fixture-pack")));
            assertThrows(UnsupportedOperationException.class, () -> textures.notes(TINTED).add("changed"));
        }
    }

    @Test void missingTintHandlerIsDistinctFromExplicitMaterialAndMalformedIndex() throws Exception {
        var plain = state("plain"); var painted = state("painted"); var invalid = state("invalid");
        var source = source(Map.of("tinted", model(",\"tintindex\":0"), "plain", model(""),
                "painted", model(",\"tintindex\":1,\"neoforge_data\":{\"color\":\"80AABBCC\"}"), "invalid", model(",\"tintindex\":256")));
        var textures = BlockTextureSet.load(source, List.of(TINTED, plain, painted, invalid), CancellationSignal.NONE);
        assertEquals(0xFFFFFFFF, pixel(textures, plain)); assertTrue(textures.notes(plain).isEmpty());
        assertEquals(0x80AABBCC, pixel(textures, painted), "明确材质色仍生效，但不能冒充索引处理器");
        assertTrue(textures.notes(painted).stream().anyMatch(n -> n.contains("颜色未解析")));
        assertNull(textures.surfaces(invalid)); assertTrue(textures.fallbackReason(invalid).contains("tintindex"));
        assertNotNull(textures.surfaces(TINTED), "一个坏索引不能使其它模型失效");
    }

    @Test void partialColourDiagnosticReachesSceneAndSurvivesCacheReopen() throws Exception {
        var area = GenericModelScene.area(List.of(TINTED, TINTED), List.of());
        var textures = BlockTextureSet.load(source(Map.of("tinted", model(",\"tintindex\":0"))), area, CancellationSignal.NONE);
        var snapshot = new VoxelSceneBuilder(textures).build(area, CancellationSignal.NONE).snapshot().orElseThrow();
        var warnings = snapshot.warnings().stream().filter(w -> w.code() == MeshWarning.Code.MODEL_APPROXIMATION && w.detail().contains("颜色未解析")).toList();
        assertEquals(1, warnings.size()); assertEquals(2, warnings.getFirst().occurrences());
        assertTrue(warnings.getFirst().detail().contains(TINTED.name())); assertTrue(snapshot.faceCount() > 0);
        var key = new ViewerAreaKey(area.world(), area.dimension().id(), area.target(), VoxelPreviewCoordinator.RENDER_VERSION);
        var signature = new RegionSourceSignature(List.of()); var cache = new ViewerSceneCache(ViewerPerformanceProfile.BASIC);
        assertTrue(cache.put(key, snapshot, signature));
        assertEquals(snapshot.warnings(), cache.get(key, signature).orElseThrow().warnings());
        cache.clear(); assertTrue(cache.get(key, signature).isEmpty());
    }

    @Test void emptyModelsHaveNoColourWarningAndLowLevelBakingCannotSilentlyGuess() throws Exception {
        var empty = state("empty");
        var textures = BlockTextureSet.load(source(Map.of("empty", "{\"elements\":[]}")), List.of(empty), CancellationSignal.NONE);
        assertNotNull(textures.surfaces(empty)); assertTrue(textures.surfaces(empty).isEmpty()); assertTrue(textures.notes(empty).isEmpty());
        var white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB); white.setRGB(0, 0, 0xFFFFFFFF);
        assertThrows(IOException.class, () -> BlockTextureSet.composite(white, 0, null, -1));
        assertThrows(IOException.class, () -> BlockTextureSet.composite(white, -1, white, 255));
        assertEquals(0xFF123456, BlockTextureSet.composite(white, 0x1123456, null, -1).getRGB(0, 0));
        assertEquals(0xFF000000, BlockTextureSet.composite(white, 0x1000000, null, -1).getRGB(0, 0));
    }

    @Test void manyIndicesUseOneBoundedDiagnosticRatherThanOneWarningPerFace() throws Exception {
        var root = com.google.gson.JsonParser.parseString(model("")).getAsJsonObject();
        var elements = new com.google.gson.JsonArray();
        for (int index = 0; index < 12; index++) {
            var element = root.getAsJsonArray("elements").get(0).deepCopy().getAsJsonObject();
            element.getAsJsonObject("faces").getAsJsonObject("up").addProperty("tintindex", index);
            elements.add(element);
        }
        root.add("elements", elements);
        var textures = BlockTextureSet.load(source(Map.of("tinted", root.toString())), List.of(TINTED), CancellationSignal.NONE);
        assertNotNull(textures.surfaces(TINTED));
        var notes = textures.notes(TINTED).stream().filter(n -> n.contains("颜色未解析")).toList();
        assertEquals(1, notes.size()); assertTrue(notes.getFirst().contains("[0, 1, 2, 3, 4, 5, 6, 7 等 12 项]"));
        assertEquals(0xFFFFFFFF, pixel(textures, TINTED));
    }
}
