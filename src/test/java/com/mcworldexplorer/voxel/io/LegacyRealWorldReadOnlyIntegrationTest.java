package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.map.MapTileGenerationMonitor;
import com.mcworldexplorer.map.MapTileGenerationResult;
import com.mcworldexplorer.map.MapTileGenerator;
import com.mcworldexplorer.map.MapTileKey;
import com.mcworldexplorer.map.MapZoomLevel;
import com.mcworldexplorer.nbt.LevelDatReader;
import com.mcworldexplorer.nbt.SavedLegacyIdMap;
import com.mcworldexplorer.preview.PreviewCenter;
import com.mcworldexplorer.preview.PreviewCenterResolver;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.preview.WorldDimensionDiscovery;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import com.mcworldexplorer.voxel.mesh.VoxelSceneResult;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import com.mcworldexplorer.voxel.mesh.VoxelTargetIndex;
import com.mcworldexplorer.voxel.resource.ClientResourceLocator;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-only integration verification against the local 1.7.10 Forge sample save.
 * Verifies zero writes (size, mtime, SHA-256), 2D map generation without unreadable chunks,
 * 3D simplified scene generation, and crosshair block identity lookup.
 */
class LegacyRealWorldReadOnlyIntegrationTest {
    private record FileFingerprint(long size, long lastModifiedMillis, String sha256) { }

    @Test
    void readsLocal1710ForgeSaveReadOnlyAcross2DAnd3DPipelines() throws Exception {
        String configured = System.getProperty("mcwe.legacy1710World", System.getenv("MCWORLD_LEGACY_1710_WORLD"));
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "Set MCWORLD_LEGACY_1710_WORLD or -Dmcwe.legacy1710World to run real 1.7.10 world verification");
        Path sampleWorld = Path.of(configured);
        Assumptions.assumeTrue(Files.isDirectory(sampleWorld),
                "Configured 1.7.10 Forge sample save directory does not exist");

        Map<Path, FileFingerprint> before = snapshotWorldFiles(sampleWorld);
        assertFalse(before.isEmpty(), "Expected level.dat and region/*.mca files in sample world");

        WorldInfo world = LevelDatReader.readLevelDat(sampleWorld);
        assertTrue(world.isParsed());
        assertEquals(LevelDatReader.LEGACY_PRE_1_9_VERSION, world.getVersionName());

        SavedLegacyIdMap idMap = SavedLegacyIdMap.readFromWorld(sampleWorld);
        assertEquals(SavedLegacyIdMap.Source.FML_ITEM_DATA, idMap.source());
        assertTrue(idMap.size() > 0, "Expected Forge FML.ItemData block mappings");

        List<WorldDimension> dimensions = WorldDimensionDiscovery.discover(world);
        assertFalse(dimensions.isEmpty());
        WorldDimension overworld = dimensions.getFirst();
        assertEquals(WorldDimension.OVERWORLD_ID, overworld.id());

        var resourceCheck = new ClientResourceLocator().locate(
                sampleWorld, world.getVersionName(), Optional.empty(), List.of(), CancellationSignal.NONE);
        assertEquals(ClientResourceLocator.Status.INVALID_VERSION, resourceCheck.status());
        assertTrue(resourceCheck.message().contains("预扁平化旧版存档"));

        PreviewCenter center = PreviewCenterResolver.resolve(world);
        long tileX = Math.floorDiv(center.x(), 256);
        long tileZ = Math.floorDiv(center.z(), 256);
        MapTileKey tileKey = new MapTileKey(
                WorldCachePaths.worldDirectoryName(world),
                overworld.id(),
                PreviewLayer.surfaceOverview(),
                MapZoomLevel.fromBlocksPerPixel(1),
                tileX,
                tileZ,
                "v076-cp5-check");
        MapTileGenerationResult tileResult = new MapTileGenerator().generate(
                world, overworld, tileKey, MapTileGenerationMonitor.NONE);
        assertEquals(0, tileResult.failedChunks(), "2D tile must have zero failed chunks");
        assertTrue(tileResult.unreadable().isEmpty(), "2D tile must have zero unreadable error stripes");
        assertTrue(tileResult.sampledChunks() > 0, "2D tile must sample real chunks");
        assertTrue(tileResult.populatedColumns() > 0, "2D tile must populate surface columns");

        int centerChunkX = Math.floorDiv(center.x(), 16);
        int centerChunkZ = Math.floorDiv(center.z(), 16);
        ChunkRectangle target = ChunkRectangle.target(centerChunkX, centerChunkZ, centerChunkX, centerChunkZ);
        VoxelAreaLoadResult area = new VoxelAreaLoader().load(
                new VoxelAreaRequest(sampleWorld, overworld.id(), target),
                CancellationSignal.NONE);
        assertEquals(ChunkLoadStatus.SUCCESS, area.results().get(target.coordinates().getFirst()).status());

        VoxelSceneResult sceneResult = new VoxelSceneBuilder().build(area, CancellationSignal.NONE);
        VoxelSceneSnapshot snapshot = sceneResult.snapshot().orElseThrow();
        assertTrue(snapshot.blockCount() > 0, "3D scene must contain non-air blocks");
        assertTrue(snapshot.faceCount() > 0, "3D scene must generate visible faces");

        VoxelTargetIndex targetIndex = new VoxelTargetIndex(snapshot);
        double rayX = (centerChunkX * 16 + 8.5) - snapshot.anchorWorldX();
        double rayZ = (centerChunkZ * 16 + 8.5) - snapshot.anchorWorldZ();
        double rayY = snapshot.bounds().maxY() + 2.0;
        VoxelTargetIndex.Query hitQuery = targetIndex.query(rayX, rayY, rayZ, 0.0, -1.0, 0.0);
        assertTrue(hitQuery.hit().isPresent(), "Crosshair raycast must hit a block in the 3D scene");
        VoxelTargetIndex.Hit hit = hitQuery.hit().orElseThrow();
        assertNotNull(hit.entry().original().name());
        assertFalse(hit.entry().original().name().isBlank());

        Map<Path, FileFingerprint> after = snapshotWorldFiles(sampleWorld);
        assertEquals(before, after, "Sample world files must be 100% unmodified after read-only inspection");
    }

    private static Map<Path, FileFingerprint> snapshotWorldFiles(Path worldFolder) throws IOException {
        Map<Path, FileFingerprint> result = new LinkedHashMap<>();
        Path levelDat = worldFolder.resolve("level.dat");
        if (Files.isRegularFile(levelDat)) {
            result.put(levelDat, fingerprint(levelDat));
        }
        Path regionDir = worldFolder.resolve("region");
        if (Files.isDirectory(regionDir)) {
            try (Stream<Path> stream = Files.list(regionDir)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".mca"))
                        .sorted()
                        .toList()) {
                    result.put(file, fingerprint(file));
                }
            }
        }
        return result;
    }

    private static FileFingerprint fingerprint(Path file) throws IOException {
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return new FileFingerprint(size, mtime, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
