package com.mcworldexplorer.map;

import com.mcworldexplorer.region.RegionHeaderIndex;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import java.util.regex.Pattern;

/** Only filenames and 8192-byte headers. Never opens chunk bodies or runs the sampler. */
public final class SavedRangeIndex {
    public static final int MAX_REGIONS = 4096; // Conservative <= 8 MiB including snapshots and diagnostics.
    public static final int WIDTH = 640, HEIGHT = 400;
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    public record Region(int x, int z, long[] occupied, long[] invalid, String failure) {
        public Region { occupied = occupied.clone(); invalid = invalid.clone(); }
        @Override public long[] occupied() { return occupied.clone(); }
        @Override public long[] invalid() { return invalid.clone(); }
        public boolean hasInvalid() { return invalid.length > 0; }
        public boolean occupied(int slot) { return slot / 64 < occupied.length && (occupied[slot / 64] & (1L << slot)) != 0; }
        public boolean invalid(int slot) { return slot / 64 < invalid.length && (invalid[slot / 64] & (1L << slot)) != 0; }
    }
    public record Bounds(long minX, long minZ, long maxX, long maxZ) {
        public double scale() { return Math.max((maxX - minX) / (double)(WIDTH - 16), (maxZ - minZ) / (double)(HEIGHT - 16)); }
        public double worldX(double pixel) { return (minX + (double) maxX) / 2 + (pixel - WIDTH / 2.0) * scale(); }
        public double worldZ(double pixel) { return (minZ + (double) maxZ) / 2 + (pixel - HEIGHT / 2.0) * scale(); }
        public double screenX(double world) { return WIDTH / 2.0 + (world - (minX + (double)maxX) / 2) / scale(); }
        public double screenZ(double world) { return HEIGHT / 2.0 + (world - (minZ + (double)maxZ) / 2) / scale(); }
    }
    public record Snapshot(List<Region> regions, long chunks, long invalidChunks, int failures,
                           boolean complete, boolean limited, Bounds bounds, int[] pixels) {
        public Snapshot { regions = List.copyOf(regions); }
    }
    public static void scan(Path directory, BooleanSupplier cancelled, Consumer<Snapshot> publish) throws IOException {
        scan(directory, cancelled, publish, MAX_REGIONS);
    }
    static void scan(Path directory, BooleanSupplier cancelled, Consumer<Snapshot> publish, int limit) throws IOException {
        var regions = new ArrayList<Region>(); long chunks = 0, invalidChunks = 0; int failures = 0;
        boolean limited = false; long last = System.nanoTime(); int since = 0;
        try (var files = Files.newDirectoryStream(directory, "r.*.*.mca")) {
            for (Path file : files) {
                check(cancelled);
                var match = NAME.matcher(file.getFileName().toString()); if (!match.matches()) continue;
                if (regions.size() >= limit) { limited = true; break; }
                int x, z;
                try { x = Integer.parseInt(match.group(1)); z = Integer.parseInt(match.group(2)); }
                catch (NumberFormatException invalid) { failures++; continue; }
                try {
                    var header = RegionHeaderIndex.read(file); var occupied = new BitSet(1024); var bad = new BitSet(1024);
                    for (int slot : header.occupiedSlots()) {
                        occupied.set(slot); chunks++;
                        if (header.state(slot % 32, slot / 32) == RegionHeaderIndex.State.INVALID) { bad.set(slot); invalidChunks++; }
                    }
                    regions.add(new Region(x, z, occupied.toLongArray(), bad.toLongArray(), ""));
                } catch (IOException | SecurityException error) {
                    String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
                    regions.add(new Region(x, z, new long[0], new long[0], reason.substring(0, Math.min(256, reason.length())))); failures++;
                }
                if (++since >= 128 || System.nanoTime() - last > 200_000_000L) {
                    check(cancelled); publish.accept(snapshot(regions, chunks, invalidChunks, failures, false, false));
                    since = 0; last = System.nanoTime();
                }
            }
        } catch (NoSuchFileException absent) { /* No saved Region directory. */ }
        check(cancelled); publish.accept(snapshot(regions, chunks, invalidChunks, failures, !limited, limited));
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Range indexing cancelled");
    }
    private static Snapshot snapshot(List<Region> regions, long chunks, long invalid, int failures, boolean complete, boolean limited) {
        long minX = Long.MAX_VALUE, minZ = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (var r : regions) {
            if (r.occupied.length == 0 && r.failure.isEmpty()) continue;
            minX = Math.min(minX, (long) r.x * 512); minZ = Math.min(minZ, (long) r.z * 512);
            maxX = Math.max(maxX, ((long) r.x + 1) * 512); maxZ = Math.max(maxZ, ((long) r.z + 1) * 512);
        }
        Bounds bounds = minX == Long.MAX_VALUE ? null : new Bounds(minX, minZ, maxX, maxZ);
        int[] pixels = new int[WIDTH * HEIGHT]; Arrays.fill(pixels, 0xff20262b);
        if (bounds != null) for (var r : regions) {
            if (!r.failure.isEmpty()) { fill(pixels, bounds, (long)r.x * 512, (long)r.z * 512, 512, 0xffd45c58); continue; }
            for (int slot = 0; slot < 1024; slot++) if (r.occupied(slot))
                fill(pixels, bounds, (long)r.x * 512 + slot % 32 * 16, (long)r.z * 512 + slot / 32 * 16, 16,
                        r.invalid(slot) ? 0xffd45c58 : 0xff668db6);
        }
        return new Snapshot(regions, chunks, invalid, failures, complete, limited, bounds, pixels);
    }
    private static void fill(int[] pixels, Bounds bounds, long x, long z, int size, int color) {
        int x0 = Math.max(0, (int)Math.floor(bounds.screenX(x))), z0 = Math.max(0, (int)Math.floor(bounds.screenZ(z)));
        int x1 = Math.min(WIDTH, Math.max(x0 + 1, (int)Math.ceil(bounds.screenX(x + size))));
        int z1 = Math.min(HEIGHT, Math.max(z0 + 1, (int)Math.ceil(bounds.screenZ(z + size))));
        for (int row = z0; row < z1; row++) for (int col = x0; col < x1; col++) {
            int i = col + row * WIDTH; if (pixels[i] != 0xffd45c58) pixels[i] = color;
        }
    }
}
