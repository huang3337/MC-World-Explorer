package com.mcworldexplorer.ui;

import com.mcworldexplorer.voxel.data.ChunkRectangle;

import java.util.Objects;

/**
 * Keeps a chosen world area fixed until explicitly cleared, independently of map rendering.
 * The controller's request ID still rejects callbacks belonging to a different loading request.
 */
final class ViewerSelectionState {
    private enum Phase { SELECTED, LOADING, LOADED }

    private ChunkRectangle candidate;
    private ChunkRectangle locked;
    private Phase phase = Phase.SELECTED;

    ChunkRectangle candidate() {
        return candidate;
    }

    ChunkRectangle locked() {
        return locked;
    }

    ChunkRectangle active() {
        return locked != null ? locked : candidate;
    }

    boolean canSelect() {
        return locked == null;
    }

    boolean loading() {
        return phase == Phase.LOADING;
    }

    boolean loaded() {
        return phase == Phase.LOADED;
    }

    void preview(ChunkRectangle selection) {
        if (canSelect()) {
            candidate = selection;
        }
    }

    boolean lock(ChunkRectangle selection) {
        Objects.requireNonNull(selection, "selection");
        if (locked != null) {
            return locked.equals(selection);
        }
        locked = selection;
        candidate = null;
        phase = Phase.SELECTED;
        return true;
    }

    void beginLoading() {
        if (locked == null) {
            throw new IllegalStateException("Select an area before loading it");
        }
        candidate = null;
        phase = Phase.LOADING;
    }

    void finishLoading(boolean success) {
        if (phase == Phase.LOADING) {
            phase = success ? Phase.LOADED : Phase.SELECTED;
        }
    }

    void clearCandidate() {
        candidate = null;
    }

    void clear() {
        candidate = null;
        locked = null;
        phase = Phase.SELECTED;
    }

    void invalidateLoaded() {
        if (phase == Phase.LOADED) {
            phase = Phase.SELECTED;
        }
    }
}
