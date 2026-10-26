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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DynamicBlockResourcesTest {
    @TempDir Path home;
    private final BlockState red = new BlockState("example:unregistered", Map.of("shade", "red", "powered", "false"));
    private final BlockState blue = new BlockState("example:unregistered", Map.of("shade", "blue", "powered", "true"));

    @Test void discoversOnlyUsedTargetStatesAndTexturesAnUnregisteredNamespace() throws Exception {
        BlockState unused = new BlockState("example:unused_palette_entry", Map.of());
        var area = area(List.of(BlockState.AIR, red, blue, unused));
        try (var archive = ClientResourceArchive.open(fixture(), "1.21.1")) {
            var textures = BlockTextureSet.load(archive, area, CancellationSignal.NONE);
            assertNotNull(textures.surfaces(red));
            assertNotNull(textures.surfaces(blue));
            assertNull(textures.surfaces(unused));
            assertTrue(textures.fallbackReason(unused).contains("未参与"), "未使用的 palette 项不应读取资源");
            assertNull(textures.surfaces(new BlockState(red.name(), Map.of("shade", "red"))),
                    "完整 Properties 必须参与状态身份，不能按名称裁剪");
            assertNotEquals(textures.surfaces(red).stream().filter(f->f.direction()==BlockModelResolver.Direction.UP).findFirst().orElseThrow().uv(),
                    textures.surfaces(blue).stream().filter(f->f.direction()==BlockModelResolver.Direction.UP).findFirst().orElseThrow().uv());
            var scene = new VoxelSceneBuilder(textures).build(area, CancellationSignal.NONE).snapshot().orElseThrow();
            assertEquals(2, scene.blockCount());
            assertEquals(12, scene.faceCount());
            assertTrue(scene.chunks().getFirst().batches().stream().allMatch(MeshBatch::textured));
            assertTrue(scene.chunks().getFirst().batches().stream().allMatch(batch -> batch.layer() == RenderLayer.OPAQUE));
            assertTrue(scene.warnings().stream().noneMatch(w -> w.code() == MeshWarning.Code.FALLBACK_BLOCK
                    || w.code() == MeshWarning.Code.TEXTURE_FALLBACK), "已解析的标准 Mod 资源不应仍报告未知紫色降级");
        }
    }

    @Test void deduplicatesCompleteStatesAndBoundsPreparationWithoutLosingTheOverflowReason() throws Exception {
        try (var archive = ClientResourceArchive.open(fixture(), "1.21.1")) {
            List<BlockState> duplicates = new ArrayList<>(Collections.nCopies(BlockTextureSet.MAX_STATES + 1, red));
            duplicates.add(blue);
            var textures = BlockTextureSet.load(archive, duplicates, CancellationSignal.NONE);
            assertNotNull(textures.surfaces(blue), "重复状态不能耗尽不同状态预算");
        }
        try (var archive = ClientResourceArchive.open(fixture(), "1.21.1")) {
            List<BlockState> many = new ArrayList<>();
            for (int i = 0; i <= BlockTextureSet.MAX_STATES; i++)
                many.add(new BlockState("example:missing_" + i, Map.of()));
            var textures = BlockTextureSet.load(archive, many, CancellationSignal.NONE);
            assertTrue(textures.fallbackReason(many.getFirst()).contains("MISSING_RESOURCE"));
            assertTrue(textures.fallbackReason(many.getLast()).contains("超过"));
        }
    }

    @Test void corruptChunkDoesNotAbortOtherTargetChunksDuringResourceCollection() throws Exception {
        var good = area(List.of(BlockState.AIR, red, blue));
        var target = ChunkRectangle.target(0, 0, 1, 0);
        var brokenCoordinate = new ChunkCoordinate(1, 0);
        long[] invalid = new long[256]; invalid[0] = 15; // Three palette entries, encoded index 15 is invalid.
        var broken = new VoxelChunk(1, 0, List.of(new ChunkSection(0, List.of(BlockState.AIR, red, blue), invalid)));
        Map<ChunkCoordinate, ChunkLoadResult> results = new LinkedHashMap<>();
        for (var c : target.expanded(1).coordinates())
            results.put(c, good.results().getOrDefault(c, ChunkLoadResult.withoutChunk(c, ChunkLoadStatus.MISSING, "missing", 0, 0)));
        results.put(brokenCoordinate, ChunkLoadResult.success(brokenCoordinate, broken, 0, 0));
        var combined = new VoxelAreaLoadResult(home, WorldDimension.overworld(home), target, results);
        try (var archive = ClientResourceArchive.open(fixture(), "1.21.1")) {
            var textures = BlockTextureSet.load(archive, combined, CancellationSignal.NONE);
            assertNotNull(textures.surfaces(red));
            var scene = new VoxelSceneBuilder(textures).build(combined, CancellationSignal.NONE);
            assertEquals(ChunkLoadStatus.CORRUPT, scene.targetStatuses().get(brokenCoordinate));
            assertEquals(2, scene.snapshot().orElseThrow().blockCount());
        }
    }

    @Test void cancelsActualAreaCollectionAndLeavesTheArchiveUsable() throws Exception {
        try (var archive = ClientResourceArchive.open(fixture(), "1.21.1")) {
            AtomicInteger checks = new AtomicInteger();
            assertThrows(CancellationException.class, () -> BlockTextureSet.load(archive,
                    area(List.of(BlockState.AIR, red, blue)), () -> checks.incrementAndGet() >= 3));
            assertNotNull(BlockTextureSet.load(archive, List.of(red), CancellationSignal.NONE).surfaces(red));
        }
    }

    @Test void resolvesPreviouslyUnlistedRealFullCubesWithoutRegistration() throws Exception {
        String configured = System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
        assumeTrue(configured != null);
        List<BlockState> requested = List.of("iron_block", "diamond_block", "gold_block", "granite")
                .stream().map(name -> new BlockState("minecraft:" + name, Map.of())).toList();
        try (var archive = ClientResourceArchive.open(Path.of(configured), "1.21.1")) {
            var textures = BlockTextureSet.load(archive, requested, CancellationSignal.NONE);
            for (var state : requested)
                assertNotNull(textures.surfaces(state), state + ": " + textures.fallbackReason(state));
        }
    }

    @Test void derivedTextureBudgetFailsOnlyTheLargeStateAndKeepsFollowingModelsUsable() throws Exception {
        List<String> faces=new ArrayList<>();for(String direction:List.of("down","up","north","south","west","east"))faces.add("\""+direction+"\":{\"texture\":\"example:block/plain\"}");
        List<String> elements=new ArrayList<>();
        for(int i=0;i<23;i++){final int index=i;int x=i%8*2,y=i/8*2;elements.add("{\"from\":["+x+","+y+",0],\"to\":["+(x+1)+","+(y+1)+",1],\"faces\":{"+java.util.stream.IntStream.range(0,6).mapToObj(j->faces.get(j).replace("example:block/plain","example:block/plain"+(index*6+j))).collect(java.util.stream.Collectors.joining(","))+"}}");}
        var output=new ByteArrayOutputStream();var image=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);ImageIO.write(image,"png",output);
        Map<String,byte[]> resources=new HashMap<>();for(int i=0;i<138;i++)resources.put("assets/example/textures/block/plain"+i+".png",output.toByteArray());
        for(String name:List.of("large","small")) {
            resources.put("assets/example/blockstates/"+name+".json",("{\"variants\":{\"\":{\"model\":\"example:block/"+name+"\"}}}").getBytes(StandardCharsets.UTF_8));
            resources.put("assets/example/models/block/"+name+".json",("{\"elements\":["+(name.equals("large")?String.join(",",elements):elements.getFirst())+"]}").getBytes(StandardCharsets.UTF_8));
        }
        BlockResourceSource source=new BlockResourceSource(){
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(home,1,1,"synthetic");}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(resources.get(id.entryName(kind)));}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return read(kind,id).map(bytes->new String(bytes,StandardCharsets.UTF_8));}
        };
        var large=new BlockState("example:large",Map.of());var small=new BlockState("example:small",Map.of());
        var textures=BlockTextureSet.load(source,List.of(large,small),CancellationSignal.NONE);
        assertNull(textures.surfaces(large));assertTrue(textures.fallbackReason(large).contains("32 MiB"));
        assertNotNull(textures.surfaces(small),"大状态的工作预算失败不能阻断后续正常状态");
    }

    @Test void sourceClosureAfterLastPngCannotPublishAnApparentlyValidPreparedSet() throws Exception {
        try(var client=ClientResourceArchive.open(fixture(),"1.21.1")) {
            BlockResourceSource source=new BlockResourceSource(){
                public ClientResourceArchive.Fingerprint fingerprint(){return client.fingerprint();}
                public void validate() throws java.io.IOException {client.validate();}
                public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws java.io.IOException {
                    var result=client.read(kind,id);if(kind==ResourceLocation.Kind.TEXTURE)client.close();return result;
                }
                public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws java.io.IOException{return client.readJson(kind,id);}
            };
            var failure=assertThrows(ResourceAccessException.class,()->BlockTextureSet.load(source,List.of(red),CancellationSignal.NONE));
            assertEquals(ResourceAccessException.Reason.CLOSED,failure.reason());
        }
    }

    private VoxelAreaLoadResult area(List<BlockState> palette) throws Exception {
        long[] data = new long[256];
        data[0] = 1L << 4 | 2L << 16; // Two isolated actual blocks; other palette entries are unused.
        var coordinate = new ChunkCoordinate(0, 0);
        var target = ChunkRectangle.target(0, 0, 0, 0);
        var chunk = new VoxelChunk(0, 0, List.of(new ChunkSection(0, palette, data)));
        Map<ChunkCoordinate, ChunkLoadResult> results = new LinkedHashMap<>();
        for (var c : target.expanded(1).coordinates())
            results.put(c, ChunkLoadResult.withoutChunk(c, ChunkLoadStatus.MISSING, "missing", 0, 0));
        results.put(coordinate, ChunkLoadResult.success(coordinate, chunk, 0, 0));
        return new VoxelAreaLoadResult(home, WorldDimension.overworld(home), target, results);
    }

    private Path fixture() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        put(entries, "version.json", "{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}");
        put(entries, "assets/minecraft/blockstates/stone.json", "{}");
        put(entries, "assets/minecraft/models/block/stone.json", "{}");
        entries.put("assets/minecraft/textures/block/stone.png", png(0xFF777777));
        put(entries, "assets/example/blockstates/unregistered.json", "{\"variants\":{\"shade=red\":{\"model\":\"example:block/red\"},\"shade=blue\":{\"model\":\"example:block/blue\"}}}");
        List<String> faces = new ArrayList<>();
        for (String direction : List.of("down", "up", "north", "south", "west", "east"))
            faces.add("\"" + direction + "\":{\"texture\":\"#all\"}");
        put(entries, "assets/example/models/block/template.json", "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{" + String.join(",", faces) + "}}]}");
        for (String color : List.of("red", "blue")) {
            put(entries, "assets/example/models/block/" + color + ".json", "{\"parent\":\"example:block/template\",\"textures\":{\"all\":\"example:block/" + color + "\"}}");
            entries.put("assets/example/textures/block/" + color + ".png", png(color.equals("red") ? 0xFFFF0000 : 0xFF0000FF));
        }
        Path jar = home.resolve(UUID.randomUUID() + ".jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return jar;
    }
    private static void put(Map<String, byte[]> entries, String name, String text) {
        entries.put(name, text.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] png(int color) throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }
}
