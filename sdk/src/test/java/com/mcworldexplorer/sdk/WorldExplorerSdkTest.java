package com.mcworldexplorer.sdk;

import com.mcworldexplorer.map.MapSamplingFixtures;
import com.mcworldexplorer.preview.BlockColorPalette;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import com.mcworldexplorer.world.WorldInfo;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class WorldExplorerSdkTest {

    @TempDir
    Path tempDir;

    @Test
    void versionReturnsCurrentBuildVersion() {
        String version = WorldExplorerSdk.version();
        assertNotNull(version);
        assertEquals("0.8.1", version);
    }

    @Test
    void openScanBuildSceneAndExportSolidObjWithoutModifyingWorld() throws Exception {
        Path worldDir = createSyntheticWorld(tempDir.resolve("saves").resolve("converter_world"), "minecraft:stone", 4);
        Map<Path, String> beforeDigests = snapshotFileDigests(worldDir);

        List<WorldInfo> scanned = WorldExplorerSdk.scanWorlds(tempDir.resolve("saves"));
        assertEquals(1, scanned.size());
        assertEquals("Converter Test World", scanned.getFirst().getLevelName());

        McWorld world = WorldExplorerSdk.openWorld(worldDir);
        assertEquals(worldDir.toAbsolutePath().normalize(), world.directory());
        assertEquals("Converter Test World", world.info().getLevelName());
        assertTrue(world.findDimension(WorldDimension.OVERWORLD_ID).isPresent());
        assertTrue(world.findDimension("DIM0").isPresent());
        assertThrows(IllegalArgumentException.class, () -> world.dimension("custom:missing"));

        ChunkRectangle target = ChunkRectangle.target(0, 0, 0, 0);
        VoxelSceneSnapshot scene = world.buildScene(target);
        assertTrue(scene.atlas().isEmpty());
        assertTrue(scene.vertexCount() > 0);
        assertTrue(scene.faceCount() > 0);
        assertEquals(16 * 16 * 16, scene.blockCount());

        Path exportDir = tempDir.resolve("exports");
        ObjExportResult export = world.exportWavefrontObj(SceneBuildRequest.of(target), exportDir, "stone_chunk");
        assertTrue(Files.isRegularFile(export.objFile()));
        assertTrue(Files.isRegularFile(export.mtlFile()));
        assertTrue(export.textureFile().isEmpty());
        assertEquals(scene.vertexCount(), export.vertexCount());
        assertTrue(export.faceCount() > 0);
        assertTrue(export.materialCount() >= 1);

        String obj = Files.readString(export.objFile(), StandardCharsets.UTF_8);
        String mtl = Files.readString(export.mtlFile(), StandardCharsets.UTF_8);
        assertTrue(obj.contains("mtllib stone_chunk.mtl"));
        assertTrue(obj.contains("usemtl solid_opaque_"));
        assertTrue(obj.contains("\nv "));
        assertTrue(obj.contains("\nvn "));
        assertTrue(obj.contains("\nf "));
        assertTrue(mtl.contains("newmtl solid_opaque_"));
        assertTrue(mtl.contains("Kd "));

        assertEquals(beforeDigests, snapshotFileDigests(worldDir), "World directory must remain strictly read-only");
    }

    @Test
    void renderMapImageAndExportMapPngHeadless() throws Exception {
        Path worldDir = createSyntheticWorld(tempDir.resolve("map_world"), "minecraft:stone", 4);
        McWorld world = WorldExplorerSdk.openWorld(worldDir);

        MapRenderRequest request = MapRenderRequest.ofBlockRange(0, 0, 15, 15);
        BufferedImage image = world.renderMapImage(request);
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());

        int expectedStone = 0xFF000000 | BlockColorPalette.resolve("minecraft:stone").rgb();
        assertEquals(expectedStone, image.getRGB(8, 8));

        Path pngFile = tempDir.resolve("out").resolve("surface.png");
        Path written = world.exportMapPng(
                MapRenderRequest.builder(0, 0, 15, 15)
                        .layer(PreviewLayer.surfaceOverview())
                        .blocksPerPixel(2)
                        .build(),
                pngFile);
        assertEquals(pngFile.toAbsolutePath().normalize(), written);
        assertTrue(Files.isRegularFile(written));
        BufferedImage loaded = ImageIO.read(written.toFile());
        assertNotNull(loaded);
        assertEquals(8, loaded.getWidth());
        assertEquals(8, loaded.getHeight());
        assertEquals(expectedStone, loaded.getRGB(4, 4));
    }

    @Test
    void requestsValidateBoundsAndRespectCancellation() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> SceneBuildRequest.builderForBlockRange(16, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SceneBuildRequest.builderForBlockRange(Long.MIN_VALUE, 0, Long.MIN_VALUE + 15, 15));
        assertThrows(IllegalArgumentException.class,
                () -> SceneBuildRequest.builder(ChunkRectangle.target(0, 0, 0, 0))
                        .instanceDirectory(tempDir)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> MapRenderRequest.ofBlockRange(10, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> MapRenderRequest.ofBlockRange(Long.MIN_VALUE, 0, Long.MIN_VALUE + 15, 15));
        assertThrows(IllegalArgumentException.class,
                () -> MapRenderRequest.ofBlockRange(-40_000_000_000L, 0, -40_000_000_000L + 15, 15));
        assertThrows(IllegalArgumentException.class,
                () -> MapRenderRequest.ofBlockRange(0, 0, 5000, 10));
        assertThrows(IllegalArgumentException.class,
                () -> MapRenderRequest.builder(0, 0, 15, 15).blocksPerPixel(0).build());

        Path worldDir = createSyntheticWorld(tempDir.resolve("cancel_world"), "minecraft:stone", 4);
        McWorld world = WorldExplorerSdk.openWorld(worldDir);
        CancellationSignal cancelled = () -> true;
        assertThrows(CancellationException.class, () -> world.buildScene(
                SceneBuildRequest.builder(ChunkRectangle.target(0, 0, 0, 0))
                        .cancellation(cancelled)
                        .build()));
        assertThrows(CancellationException.class, () -> world.renderMapImage(
                MapRenderRequest.builder(0, 0, 15, 15)
                        .cancellation(cancelled)
                        .build()));
    }

    private static Path createSyntheticWorld(Path worldDir, String blockName, int sectionY) throws IOException {
        Files.createDirectories(worldDir.resolve("region"));
        CompoundBinaryTag version = CompoundBinaryTag.builder()
                .putString("Name", "1.21.1")
                .putInt("Id", 3955)
                .build();
        CompoundBinaryTag data = CompoundBinaryTag.builder()
                .putString("LevelName", "Converter Test World")
                .putInt("GameType", 1)
                .putInt("SpawnX", 8)
                .putInt("SpawnY", 80)
                .putInt("SpawnZ", 8)
                .putLong("LastPlayed", 1_700_000_000_000L)
                .put("Version", version)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Data", data)
                .build();
        BinaryTagIO.writer().write(root, worldDir.resolve("level.dat"), BinaryTagIO.Compression.GZIP);
        MapSamplingFixtures.region(worldDir.resolve("region").resolve("r.0.0.mca"), blockName, sectionY, 1, 0);
        return worldDir;
    }

    private static Map<Path, String> snapshotFileDigests(Path directory) throws Exception {
        Map<Path, String> digests = new LinkedHashMap<>();
        try (var stream = Files.walk(directory)) {
            List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                sha256.update(Files.readAllBytes(file));
                digests.put(directory.relativize(file), HexFormat.of().formatHex(sha256.digest()));
            }
        }
        return digests;
    }
}
