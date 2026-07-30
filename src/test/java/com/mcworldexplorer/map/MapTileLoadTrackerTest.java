package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.PreviewLayer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTileLoadTrackerTest {
    private static final MapTileKey KEY = new MapTileKey(
            "world",
            "minecraft:overworld",
            PreviewLayer.surfaceOverview(),
            MapZoomLevel.BLOCKS_2,
            0,
            0,
            "test");

    @Test
    void retriesOneAutomaticFailureThenShowsTheSecondFailure() {
        MapTileLoadTracker tracker = new MapTileLoadTracker();

        tracker.ensureAutomaticLoading(KEY);

        assertTrue(tracker.isLoading(KEY));
        assertEquals(
                MapTileLoadTracker.FailureAction.RETRY_AUTOMATICALLY,
                tracker.recordFailure(KEY));
        assertTrue(tracker.isLoading(KEY));
        assertEquals(Set.of(KEY), tracker.retryingKeys(Set.of(KEY)));
        assertFalse(tracker.isFailed(KEY));
        assertEquals(
                MapTileLoadTracker.FailureAction.SHOW_FAILED,
                tracker.recordFailure(KEY));
        assertFalse(tracker.isLoading(KEY));
        assertTrue(tracker.isFailed(KEY));
    }

    @Test
    void doesNotRestartAutomaticLoadingForAFailedTile() {
        MapTileLoadTracker tracker = failedTracker();

        tracker.ensureAutomaticLoading(KEY);

        assertFalse(tracker.isLoading(KEY));
        assertTrue(tracker.isFailed(KEY));
    }

    @Test
    void manualRetryRequiresFailureAndReturnsToFailureAfterOneAttempt() {
        MapTileLoadTracker tracker = new MapTileLoadTracker();

        assertFalse(tracker.beginManualRetry(KEY));

        tracker = failedTracker();
        assertTrue(tracker.beginManualRetry(KEY));
        assertEquals(Set.of(KEY), tracker.retryingKeys(Set.of(KEY)));
        assertFalse(tracker.beginManualRetry(KEY));
        assertEquals(
                MapTileLoadTracker.FailureAction.SHOW_FAILED,
                tracker.recordFailure(KEY));
        assertTrue(tracker.isFailed(KEY));
    }

    @Test
    void successAndClearRemoveRuntimeState() {
        MapTileLoadTracker tracker = failedTracker();

        tracker.recordSuccess(KEY);

        assertFalse(tracker.isFailed(KEY));
        tracker.ensureAutomaticLoading(KEY);
        tracker.clear();
        assertFalse(tracker.isLoading(KEY));
    }

    @Test
    void countsOnlyKeysInTheRequestedSet() {
        MapTileLoadTracker tracker = failedTracker();
        MapTileKey other = new MapTileKey(
                "world",
                "minecraft:overworld",
                PreviewLayer.surfaceOverview(),
                MapZoomLevel.BLOCKS_2,
                1,
                0,
                "test");
        tracker.ensureAutomaticLoading(other);

        assertEquals(1, tracker.failedCount(Set.of(KEY, other)));
        assertEquals(1, tracker.loadingCount(Set.of(KEY, other)));
        assertEquals(Set.of(KEY), tracker.failedKeys(Set.of(KEY, other)));
        assertEquals(0, tracker.failedCount(Set.of(other)));
    }

    @Test
    void ignoresLateFailureWithoutAnActiveAttempt() {
        MapTileLoadTracker tracker = new MapTileLoadTracker();

        assertEquals(
                MapTileLoadTracker.FailureAction.IGNORE,
                tracker.recordFailure(KEY));
    }

    private static MapTileLoadTracker failedTracker() {
        MapTileLoadTracker tracker = new MapTileLoadTracker();
        tracker.ensureAutomaticLoading(KEY);
        tracker.recordFailure(KEY);
        tracker.recordFailure(KEY);
        return tracker;
    }
}
