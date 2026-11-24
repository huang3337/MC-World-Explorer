package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.data.ChunkSection;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import com.mcworldexplorer.voxel.mesh.VoxelSceneResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LegacyUppercaseBlockFallbackTest {
    @TempDir
    Path tempDir;

    @Test
    void uppercaseLegacyModBlockIdDegradesSafelyWithDiagnosticAndPreservesTargetIdentity() throws Exception {
        Path jar = tempDir.resolve("1.21.1.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeEntry(zip, "version.json", "{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}");
            writeEntry(zip, "assets/minecraft/blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}");
            writeEntry(zip, "assets/minecraft/models/block/stone.json", "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#all\"}}}],\"textures\":{\"all\":\"minecraft:block/stone\"}}");
            var png = new java.io.ByteArrayOutputStream();
            var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            javax.imageio.ImageIO.write(image, "png", png);
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(png.toByteArray());
            zip.closeEntry();
        }

        BlockState uppercaseMod = new BlockState("Thaumcraft:blockMagicalLog", Map.of("data", "5"));
        long[] data = new long[256];
        data[0] = 1L; // (0,0,0) = uppercaseMod, rest = air
        ChunkSection section = new ChunkSection(0, List.of(BlockState.AIR, uppercaseMod), data);
        VoxelChunk chunk = new VoxelChunk(0, 0, List.of(section));
        ChunkCoordinate coord = new ChunkCoordinate(0, 0);
        ChunkRectangle target = ChunkRectangle.target(0, 0, 0, 0);
        Map<ChunkCoordinate, ChunkLoadResult> results = new java.util.LinkedHashMap<>();
        for (ChunkCoordinate neighbor : target.expanded(1).coordinates()) {
            results.put(neighbor, neighbor.equals(coord)
                    ? ChunkLoadResult.success(coord, chunk, 1, 1)
                    : ChunkLoadResult.withoutChunk(neighbor, com.mcworldexplorer.voxel.io.ChunkLoadStatus.MISSING, "missing", 0, 0));
        }
        VoxelAreaLoadResult area = new VoxelAreaLoadResult(
                tempDir,
                WorldDimension.overworld(tempDir),
                target,
                results);

        try (var archive = ClientResourceArchive.open(jar, "1.21.1")) {
            BlockTextureSet textureSet = BlockTextureSet.load(archive, area, CancellationSignal.NONE);
            assertNull(textureSet.surfaces(uppercaseMod));
            assertFalse(textureSet.diagnostics(uppercaseMod).isEmpty());
            assertTrue(textureSet.fallbackReason(uppercaseMod).contains("Thaumcraft:blockMagicalLog"));

            VoxelSceneResult result = new VoxelSceneBuilder(textureSet).build(area, CancellationSignal.NONE);
            var snapshot = result.snapshot().orElseThrow();
            assertEquals(1, snapshot.blockCount());
            var targetIndex = new com.mcworldexplorer.voxel.mesh.VoxelTargetIndex(snapshot);
            var hit = targetIndex.query(
                    0.5 - snapshot.anchorWorldX(),
                    2.5,
                    0.5 - snapshot.anchorWorldZ(),
                    0, -1, 0).hit().orElseThrow();
            assertEquals(uppercaseMod, hit.entry().original());
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
