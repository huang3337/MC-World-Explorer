package com.mcworldexplorer.map;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Worker-side pixel preparation, coalescing and bounded delivery. No JavaFX dependency. */
public final class MapDisplayQueue implements AutoCloseable {
    public static final long DEFAULT_BYTES = 4L << 20; // Remaining 8 MiB of the 12 MiB pending budget belongs to cache saves.
    public record Frame(int[] pixels, List<MapMarker> markers, String diagnostic) {
        public Frame(int[] pixels, List<MapMarker> markers) { this(pixels, markers, ""); }
        public Frame { if (pixels.length != 256 * 256) throw new IllegalArgumentException("Invalid tile pixels"); markers = List.copyOf(markers); }
        public static Frame prepare(BufferedImage image, List<MapMarker> markers) {
            return new Frame(image.getRGB(0, 0, 256, 256, null, 0, 256), markers);
        }
        public long bytes() { return (long) pixels.length * 4 + markers.stream().mapToLong(m -> 192L + MapPersistence.textBytes(m.dimensionId())).sum() + 512 + MapPersistence.textBytes(diagnostic); }
    }
    public record Delivery(MapTileKey key, Frame frame, Consumer<Frame> consumer, long bytes, boolean terminal) {
        public void apply() { consumer.accept(frame); }
    }
    private final long limit;
    private final LinkedHashMap<MapTileKey, Delivery> pending = new LinkedHashMap<>();
    private Set<MapTileKey> keys = Set.of();
    private long context, bytes, peakBytes;
    private boolean closed;
    public MapDisplayQueue() { this(DEFAULT_BYTES); }
    MapDisplayQueue(long limit) { if (limit < 512) throw new IllegalArgumentException("Budget too small"); this.limit = limit; }
    public synchronized void retain(long context, Set<MapTileKey> keys) {
        if (this.context != context) { pending.clear(); bytes = 0; }
        this.context = context; this.keys = Set.copyOf(keys);
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) { var value = iterator.next(); if (!keys.contains(value.key())) { bytes -= value.bytes(); iterator.remove(); } }
        notifyAll();
    }
    public synchronized boolean offer(long context, MapTileKey key, Frame frame, boolean terminal, Consumer<Frame> consumer) {
        long size = frame == null ? 512 : frame.bytes();
        if (size > limit) throw new IllegalArgumentException("Map display result exceeds budget");
        while (!closed && this.context == context && keys.contains(key)) {
            Delivery old = pending.get(key);
            if (old != null && old.terminal() && !terminal) return false;
            if (bytes - (old == null ? 0 : old.bytes()) + size <= limit) {
                if (old != null) bytes -= old.bytes();
                pending.put(key, new Delivery(key, frame, consumer, size, terminal)); bytes += size;
                peakBytes = Math.max(peakBytes, bytes); return true;
            }
            if (!terminal) return false;
            try { wait(25); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new CancellationException("Display delivery interrupted");
            }
        }
        return false;
    }
    public synchronized Delivery poll() {
        var it = pending.values().iterator(); if (!it.hasNext()) return null;
        var result = it.next(); it.remove(); bytes -= result.bytes(); notifyAll(); return result;
    }
    public synchronized long bytes() { return bytes; }
    public synchronized long peakBytes() { return peakBytes; }
    public synchronized int size() { return pending.size(); }
    @Override public synchronized void close() { closed = true; pending.clear(); keys = Set.of(); bytes = 0; notifyAll(); }
}
