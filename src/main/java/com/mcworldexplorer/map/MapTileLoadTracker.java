package com.mcworldexplorer.map;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class MapTileLoadTracker {
    private static final int MAX_AUTOMATIC_FAILURES = 2;

    private final Map<MapTileKey, Entry> entries = new HashMap<>();

    public void ensureAutomaticLoading(MapTileKey key) {
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (!entry.failed && !entry.loading) {
            entry.loading = true;
            entry.manual = false;
        }
    }

    public boolean beginManualRetry(MapTileKey key) {
        Entry entry = entries.get(key);
        if (entry == null || !entry.failed || entry.loading) {
            return false;
        }
        entry.failed = false;
        entry.loading = true;
        entry.manual = true;
        return true;
    }

    public FailureAction recordFailure(MapTileKey key) {
        Entry entry = entries.get(key);
        if (entry == null || !entry.loading) {
            return FailureAction.IGNORE;
        }
        if (entry.manual) {
            entry.loading = false;
            entry.failed = true;
            return FailureAction.SHOW_FAILED;
        }
        entry.automaticFailures++;
        if (entry.automaticFailures < MAX_AUTOMATIC_FAILURES) {
            return FailureAction.RETRY_AUTOMATICALLY;
        }
        entry.loading = false;
        entry.failed = true;
        return FailureAction.SHOW_FAILED;
    }

    public void recordSuccess(MapTileKey key) {
        entries.remove(key);
    }

    public boolean isLoading(MapTileKey key) {
        Entry entry = entries.get(key);
        return entry != null && entry.loading;
    }

    public boolean isFailed(MapTileKey key) {
        Entry entry = entries.get(key);
        return entry != null && entry.failed;
    }

    public int loadingCount(Set<MapTileKey> keys) {
        return (int) keys.stream().filter(this::isLoading).count();
    }

    public int failedCount(Set<MapTileKey> keys) {
        return (int) keys.stream().filter(this::isFailed).count();
    }

    public Set<MapTileKey> failedKeys(Set<MapTileKey> keys) {
        return keys.stream().filter(this::isFailed).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<MapTileKey> retryingKeys(Set<MapTileKey> keys) {
        return keys.stream()
                .filter(key -> {
                    Entry entry = entries.get(key);
                    return entry != null
                            && entry.loading
                            && (entry.manual || entry.automaticFailures > 0);
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public void clear() {
        entries.clear();
    }

    public enum FailureAction {
        RETRY_AUTOMATICALLY,
        SHOW_FAILED,
        IGNORE
    }

    private static final class Entry {
        private int automaticFailures;
        private boolean loading;
        private boolean failed;
        private boolean manual;
    }
}
