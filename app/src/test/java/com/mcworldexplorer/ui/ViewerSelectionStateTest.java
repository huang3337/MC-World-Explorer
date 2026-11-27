package com.mcworldexplorer.ui;

import com.mcworldexplorer.voxel.data.ChunkRectangle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests selection transitions without a JavaFX toolkit or native window.
 * Cross-request callback identity remains the controller's request-ID responsibility.
 */
class ViewerSelectionStateTest {
    private static final ChunkRectangle ORIGINAL = ChunkRectangle.centered(-4, 7, 3, 3);
    private static final ChunkRectangle OTHER = ChunkRectangle.target(20, 30, 24, 31);

    @Test
    void candidateLocksAndOnlySuccessfulLoadingMarksItLoaded() {
        ViewerSelectionState state = new ViewerSelectionState();
        assertTrue(state.canSelect());
        assertNull(state.active());
        state.preview(ORIGINAL);
        assertEquals(ORIGINAL, state.candidate());
        assertEquals(ORIGINAL, state.active());
        assertNull(state.locked());

        assertTrue(state.lock(ORIGINAL));
        assertFalse(state.canSelect());
        assertNull(state.candidate());
        assertEquals(ORIGINAL, state.active());
        assertFalse(state.loading());
        assertFalse(state.loaded());
        state.beginLoading();
        assertTrue(state.loading());
        assertFalse(state.loaded());
        assertEquals(ORIGINAL, state.locked());

        state.finishLoading(true);
        assertFalse(state.loading());
        assertTrue(state.loaded());
        assertFalse(state.canSelect());
        assertEquals(ORIGINAL, state.active());
    }

    @Test
    void fixedAndFreeSelectionsIgnorePointerChangesUntilCleared() {
        for (ChunkRectangle selection : List.of(ORIGINAL, OTHER)) {
            ViewerSelectionState state = new ViewerSelectionState();
            state.lock(selection);
            for (int phase = 0; phase < 3; phase++) {
                if (phase == 1) state.beginLoading();
                if (phase == 2) state.finishLoading(true);
                state.preview(ChunkRectangle.centered(100, 100, 1, 1));
                state.preview(null);
                assertFalse(state.lock(ChunkRectangle.centered(100, 100, 1, 1)));
                assertEquals(selection, state.locked());
                assertEquals(selection, state.active());
                assertNull(state.candidate());
                assertFalse(state.canSelect());
            }
        }
    }

    @Test
    void lockingTheSameAreaPreservesLoadingAndCompletedStates() {
        ViewerSelectionState state = new ViewerSelectionState();
        state.lock(ORIGINAL);
        state.beginLoading();
        assertTrue(state.lock(new ChunkRectangle(ORIGINAL.minX(), ORIGINAL.minZ(),
                ORIGINAL.maxX(), ORIGINAL.maxZ())));
        assertTrue(state.loading());
        state.finishLoading(true);
        assertTrue(state.lock(ORIGINAL));
        assertTrue(state.loaded());
    }

    @Test
    void failedLoadingKeepsTheOriginalSelectionAvailableForRetry() {
        ViewerSelectionState state = new ViewerSelectionState();
        state.lock(ORIGINAL);
        state.beginLoading();
        state.finishLoading(false);
        assertFalse(state.loading());
        assertFalse(state.loaded());
        assertFalse(state.canSelect());
        assertEquals(ORIGINAL, state.active());
        state.finishLoading(true);
        assertFalse(state.loaded(), "A late success cannot replace an accepted failure");

        state.beginLoading();
        state.finishLoading(true);
        assertTrue(state.loaded());
    }

    @Test
    void duplicateTerminalCallbacksDoNotRecolorCompletedSelection() {
        ViewerSelectionState state = new ViewerSelectionState();
        state.lock(ORIGINAL);
        state.finishLoading(true);
        assertFalse(state.loaded(), "Selecting alone does not count as completed loading");
        state.beginLoading();
        state.finishLoading(true);
        state.finishLoading(false);
        state.finishLoading(true);
        assertTrue(state.loaded());
        assertEquals(ORIGINAL, state.locked());
    }

    @Test
    void cancellingClearsTheTargetAndAllowsASeparateSelection() {
        ViewerSelectionState state = new ViewerSelectionState();
        state.lock(ORIGINAL);
        state.beginLoading();
        state.clear();
        state.finishLoading(true);
        assertTrue(state.canSelect());
        assertFalse(state.loading());
        assertFalse(state.loaded());
        assertNull(state.active());
        assertNull(state.candidate());

        state.preview(OTHER);
        assertTrue(state.lock(OTHER));
        state.finishLoading(true);
        assertFalse(state.loaded(), "A callback cannot complete an area that has not started loading");
        assertEquals(OTHER, state.active());
    }

    @Test
    void reopeningCompletedAreaReturnsToLoadingWithoutMovingIt() {
        ViewerSelectionState state = new ViewerSelectionState();
        state.lock(ORIGINAL);
        state.beginLoading();
        state.finishLoading(true);
        state.beginLoading();
        assertTrue(state.loading());
        assertFalse(state.loaded());
        assertNull(state.candidate());
        assertEquals(ORIGINAL, state.active());
    }

    @Test
    void clearingCandidateDoesNotDiscardLockedOrLoadedArea() {
        ViewerSelectionState state = new ViewerSelectionState();
        state.preview(OTHER);
        state.clearCandidate();
        assertNull(state.active());
        state.lock(ORIGINAL);
        state.beginLoading();
        state.finishLoading(true);
        state.clearCandidate();
        assertEquals(ORIGINAL, state.locked());
        assertTrue(state.loaded());
    }

    @Test
    void invalidatingCompletedAppearanceKeepsItsAreaSelected() {
        ViewerSelectionState state = new ViewerSelectionState();
        state.lock(ORIGINAL);
        state.beginLoading();
        state.invalidateLoaded();
        assertTrue(state.loading(), "Invalidation must not complete or abort a current load");
        state.finishLoading(true);
        state.invalidateLoaded();
        assertFalse(state.loaded());
        assertFalse(state.loading());
        assertFalse(state.canSelect());
        assertEquals(ORIGINAL, state.active());
    }

    @Test
    void absentTargetsCannotBeLockedOrStarted() {
        ViewerSelectionState state = new ViewerSelectionState();
        state.preview(ORIGINAL);
        state.preview(null);
        assertNull(state.active());
        assertThrows(NullPointerException.class, () -> state.lock(null));
        assertThrows(IllegalStateException.class, state::beginLoading);
        assertTrue(state.canSelect());
        assertFalse(state.loading());
    }
}
