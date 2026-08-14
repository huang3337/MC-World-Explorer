package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.mesh.MeshBounds;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerSceneCacheTest {
    private static final RegionSourceSignature SIGNATURE = new RegionSourceSignature(List.of());
    private static final VoxelSceneSnapshot EMPTY_SCENE = new VoxelSceneSnapshot(
            0, 0, List.of(), new MeshBounds(0, 0, 0, 0, 0, 0),
            0, 0, 0, 0, List.of());

    @Test
    void evictsLeastRecentlyUsedEntryByCount() {
        ViewerSceneCache cache = new ViewerSceneCache(2, 1024);
        ViewerAreaKey first = key(0);
        ViewerAreaKey second = key(1);
        ViewerAreaKey third = key(2);
        cache.put(first, EMPTY_SCENE, SIGNATURE);
        cache.put(second, EMPTY_SCENE, SIGNATURE);
        assertTrue(cache.get(first, SIGNATURE).isPresent());

        cache.put(third, EMPTY_SCENE, SIGNATURE);

        assertTrue(cache.get(first, SIGNATURE).isPresent());
        assertFalse(cache.get(second, SIGNATURE).isPresent());
        assertTrue(cache.get(third, SIGNATURE).isPresent());
    }

    @Test
    void invalidatesEntryWhenRegionSignatureChanges() {
        ViewerSceneCache cache = new ViewerSceneCache(2, 1024);
        ViewerAreaKey key = key(0);
        cache.put(key, EMPTY_SCENE, SIGNATURE);
        RegionSourceSignature changed = new RegionSourceSignature(List.of(
                new RegionFileStamp(Path.of("r.0.0.mca"), false, -1, -1)));

        assertTrue(cache.get(key, changed).isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void profileChangeAppliesSmallerLimitsImmediately() {
        ViewerSceneCache cache = new ViewerSceneCache(ViewerPerformanceProfile.ENHANCED);
        for (int index = 0; index < 4; index++) {
            cache.put(key(index), EMPTY_SCENE, SIGNATURE);
        }

        cache.setProfile(ViewerPerformanceProfile.BASIC);

        assertEquals(2, cache.size());
    }

    private static ViewerAreaKey key(int x) {
        return new ViewerAreaKey(
                Path.of("world"), "minecraft:overworld",
                ChunkRectangle.target(x, 0, x, 0), "v06-r1");
    }
}
