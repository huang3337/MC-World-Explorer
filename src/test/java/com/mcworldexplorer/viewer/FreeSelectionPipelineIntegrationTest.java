package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoader;
import com.mcworldexplorer.voxel.io.VoxelAreaRequest;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FreeSelectionPipelineIntegrationTest {
    @Test
    void buildsLargerRectanglesAndCancelsWithoutChangingTheConfiguredWorld() throws Exception {
        String configured = System.getenv("MCWORLD_FREE_SELECTION_WORLD");
        assumeTrue(configured != null && !configured.isBlank());
        Path world = Path.of(configured).toRealPath();
        Map<String, String> before = manifest(world);
        try {
            for (int depth : new int[]{8, 16}) {
                ChunkRectangle target = ChunkRectangle.centered(0, 0, 16, depth);
                assertTrue(ViewerPerformanceProfile.ENHANCED.allows(target));
                long started = System.nanoTime();
                var area = new VoxelAreaLoader().load(
                        new VoxelAreaRequest(world, "minecraft:overworld", target), CancellationSignal.NONE);
                var result = new VoxelSceneBuilder().build(area, CancellationSignal.NONE);
                var scene = result.snapshot().orElseThrow();
                assertTrue(scene.faceCount() > 0);
                assertEquals(16 * depth, result.targetStatuses().size());
                assertFalse(result.cancelled());
                long thumbnailStarted = System.nanoTime();
                var thumbnail = ViewerAreaThumbnail.fromArea(area, CancellationSignal.NONE);
                assertTrue(java.util.Arrays.stream(thumbnail.argb()).anyMatch(pixel -> pixel != 0xffe8edf2));
                System.out.printf("Area thumbnail %dx%d: bytes=%d timeMs=%d%n", 16, depth,
                        thumbnail.estimatedBytes(), (System.nanoTime() - thumbnailStarted) / 1_000_000);
                System.out.printf("Free selection real sample: %dx%d blocks=%d faces=%d meshBytes=%d timeMs=%d%n",
                        16, depth, scene.blockCount(), scene.faceCount(), ViewerSceneCache.estimatedBytes(scene),
                        (System.nanoTime() - started) / 1_000_000);
            }
            AtomicBoolean cancelled = new AtomicBoolean();
            var area = new VoxelAreaLoader().load(new VoxelAreaRequest(world, "minecraft:overworld",
                            ChunkRectangle.centered(0, 0, 16, 16)), cancelled::get,
                    (coordinate, status, completed, total) -> {
                        if (completed == 4) cancelled.set(true);
                    });
            assertTrue(area.results().values().stream()
                    .anyMatch(result -> result.status() == ChunkLoadStatus.CANCELLED));
            var scene = new VoxelSceneBuilder().build(area, cancelled::get);
            assertTrue(scene.snapshot().isEmpty());
        } finally {
            assertEquals(before, manifest(world), "自由框选管线改变了原存档");
        }
    }

    private static Map<String, String> manifest(Path world) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(world)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isRegularFile(path)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                        input.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    result.put(world.relativize(path).toString(), Files.size(path) + ":"
                            + Files.getLastModifiedTime(path) + ":" + HexFormat.of().formatHex(digest.digest()));
                }
            }
        }
        return result;
    }
}
