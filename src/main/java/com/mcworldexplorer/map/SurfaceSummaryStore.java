package com.mcworldexplorer.map;

import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.region.RegionHeaderIndex;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Two bounded 8x8 pages per generation task; only successfully sampled slots are persisted. */
final class SurfaceSummaryStore implements AutoCloseable {
    private static final int MAX_PAGE_BYTES = 1 << 20;
    private final WorldInfo world;
    private final String generation;
    private final BooleanSupplier current;
    private final LinkedHashMap<PageKey, Page> pages = new LinkedHashMap<>(4, .75f, true);
    private final Path directory;
    private record PageKey(Path world, String dimension, PreviewLayer layer, String rules,
                           int x, int z, RegionHeaderIndex.Signature source) { }

    private final java.util.concurrent.atomic.AtomicBoolean disabled = new java.util.concurrent.atomic.AtomicBoolean();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SurfaceSummaryStore.class);
    private static final class Page {
        final Path path; final String identity; final RegionHeaderIndex.Signature source;
        final Map<Integer, ChunkSurfaceSummary> slots = new TreeMap<>();
        long dirtySlots;
        final long baseBytes;
        Page(Path p, String id, RegionHeaderIndex.Signature source) {
            path = p; identity = id; this.source = source;
            baseBytes = 2048 + MapPersistence.textBytes(id) + MapPersistence.textBytes(p.toString())
                    + MapPersistence.textBytes(source.toString());
            if (baseBytes > MAX_PAGE_BYTES) throw new MapPersistence.LimitExceeded();
        }
        long retainedBytes() {
            return baseBytes + slots.values().stream().mapToLong(value -> 64 + value.estimatedBytes()).sum();
        }
    }
    SurfaceSummaryStore(WorldInfo world, BooleanSupplier current) throws IOException {
        this.world = world; this.current = current; this.generation = MapCacheStorage.generation(world);
        this.directory = MapCacheStorage.root(world).resolve("summaries");
    }
    private Page page(SurfaceSummaryCache.Key key) throws IOException {
        // Most lookups hit the same page. Keep full source identity in this bounded key;
        // compute the disk address only on a page miss, not twice per sampled chunk.
        var pageKey = new PageKey(key.world(), key.dimension(), key.layer(), key.rulesVersion(),
                Math.floorDiv(key.x(), 8), Math.floorDiv(key.z(), 8), key.source());
        Page page = pages.get(pageKey);
        if (page != null) return page;
        String stableRules = key.rulesVersion();
        if (stableRules.endsWith("/" + generation)) stableRules = stableRules.substring(0, stableRules.length() - generation.length() - 1);
        String address = key.world() + "/" + key.dimension() + "/" + key.layer().cacheKey() + "/" + stableRules
                + "/" + Math.floorDiv(key.x(), 8) + "/" + Math.floorDiv(key.z(), 8);
        String identity = address + "/" + generation + "/" + key.source();
        Path path = directory.resolve(WorldCachePaths.textHash(address) + ".gz");
        if (pages.size() >= 2) {
            var it = pages.entrySet().iterator(); var old = it.next().getValue(); it.remove(); flush(old);
        }
        page = new Page(path, identity, key.source());
        synchronized (MapCacheStorage.LOCK) { read(page); }
        pages.put(pageKey, page); return page;
    }
    ChunkSurfaceSummary find(SurfaceSummaryCache.Key key) throws IOException {
        return page(key).slots.get(slot(key));
    }
    void put(SurfaceSummaryCache.Key key, ChunkSurfaceSummary value) throws IOException {
        if (disabled.get()) return;
        Page page = page(key);
        // Matching source and identity: do not rewrite a persisted slot on warm hits.
        if (page.slots.containsKey(slot(key))) return;
        page.slots.put(slot(key), value);
        page.dirtySlots |= 1L << slot(key);
        long bytes = page.retainedBytes();
        if (bytes > MAX_PAGE_BYTES) { page.slots.clear(); page.dirtySlots = 0; disabled.set(true); MapPersistence.failed(MapTileCacheResult.SaveState.ENCODING_LIMIT);
            LOG.warn("摘要页超过内存预算，停止本批次持久化"); }
    }
    private static int slot(SurfaceSummaryCache.Key key) { return Math.floorMod(key.x(), 8) + Math.floorMod(key.z(), 8) * 8; }
    private static void read(Page page) {
        try {
            if (!Files.isRegularFile(page.path) || Files.size(page.path) > MAX_PAGE_BYTES) return;
            try (var source = Files.newInputStream(page.path); var gzip = new GZIPInputStream(source);
                 var in = new DataInputStream(new LimitedInput(gzip, MAX_PAGE_BYTES))) {
                if (in.readInt() != 0x4d435033 || !in.readUTF().equals(page.identity)) return;
                long complete = in.readLong();
                long bytes = page.baseBytes;
                for (int i = 0; i < 64; i++) if ((complete & (1L << i)) != 0) {
                    var summary = new ChunkSurfaceSummary(in); bytes += 64 + summary.estimatedBytes();
                    if (bytes > MAX_PAGE_BYTES) throw new IOException("Summary page budget exceeded");
                    page.slots.put(i, summary);
                }
                if (in.read() != -1) throw new IOException("Trailing summary bytes");
            }
        } catch (IOException | RuntimeException corrupt) {
            page.slots.clear(); LOG.debug("Rebuilding summary page {}", page.path, corrupt);
        }
    }
    /** Decode directly: no readNBytes growth buffers or final raw-page copy. */
    private static final class LimitedInput extends FilterInputStream {
        private int remaining;
        LimitedInput(InputStream input, int maximum) { super(input); remaining = maximum; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && remaining-- <= 0) throw new IOException("Oversized summary page");
            return value;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return read();
            int count = in.read(target, offset, Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
    static final class LimitedOutput extends FilterOutputStream {
        private int remaining;
        LimitedOutput(OutputStream out, int limit) { super(out); remaining = limit; }
        private void charge(int count) {
            if (count < 0 || count > remaining) throw new MapPersistence.LimitExceeded();
            remaining -= count;
        }
        @Override public void write(int value) throws IOException { charge(1); out.write(value); }
        @Override public void write(byte[] bytes, int off, int length) throws IOException {
            Objects.checkFromIndexSize(off, length, bytes.length);
            charge(length); out.write(bytes, off, length);
        }
    }

    private void flush(Page page) throws IOException {
        if (page.dirtySlots == 0 || disabled.get() || !current.getAsBoolean()) return;
        page.slots.keySet().removeIf(slot -> (page.dirtySlots & (1L << slot)) == 0);
        long retained = page.retainedBytes();
        var ownerWorld = world; var ownerGeneration = generation;
        var ownerCurrent = current; var ownerDisabled = disabled;
        try { MapPersistence.submit(retained, () -> flushOwned(page, ownerWorld, ownerGeneration, ownerCurrent, ownerDisabled)); }
        catch (MapPersistence.LimitExceeded limit) { disabled.set(true); MapCacheStorageNotice.unavailable(world); MapPersistence.failed(MapTileCacheResult.SaveState.ENCODING_LIMIT); }
    }
    private static void flushOwned(Page page, WorldInfo world, String generation, BooleanSupplier current,
                                   java.util.concurrent.atomic.AtomicBoolean disabled) throws IOException {
        if (page.dirtySlots == 0 || disabled.get() || !current.getAsBoolean()) return;
        try (var encoding = MapPersistence.acquire()) {
        synchronized (MapCacheStorage.LOCK) {
            if (!current.getAsBoolean() || !generation.equals(MapCacheStorage.generation(world))) return;
            if (!page.source.equals(RegionHeaderIndex.readSignature(page.source.path())))
                throw new IOException("Source changed before summary commit");
            // Merge concurrent successful slots rather than overwriting the other task's page.
            Page merged = new Page(page.path, page.identity, page.source); read(merged);
            if (merged.slots.keySet().containsAll(page.slots.keySet())) { page.dirtySlots = 0; return; }
            merged.slots.putAll(page.slots);
            long estimate = merged.retainedBytes();
            if (estimate > MAX_PAGE_BYTES) { disabled.set(true); MapPersistence.failed(MapTileCacheResult.SaveState.ENCODING_LIMIT); return; }
            var compressed = new MapPersistence.Bytes(MAX_PAGE_BYTES);
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    new LimitedOutput(new GZIPOutputStream(compressed), MAX_PAGE_BYTES)))) {
                out.writeInt(0x4d435033); out.writeUTF(page.identity);
                long complete = 0; for (int slot : merged.slots.keySet()) complete |= 1L << slot;
                out.writeLong(complete); for (var value : merged.slots.values()) value.write(out);
            }

            if (!current.getAsBoolean()) return;
            MapPersistence.checkCancelled();
            try {
                if (!MapCacheStorage.write(world, Map.of(page.path, compressed.toByteArray()))) { disabled.set(true); MapPersistence.failed(MapTileCacheResult.SaveState.CAPACITY_LIMIT); }
            } catch (IOException unavailable) {
                MapCacheStorageNotice.unavailable(world);
                MapPersistence.failed(MapTileCacheResult.SaveState.IO_FAILED);
                disabled.set(true); LOG.warn("摘要缓存无法写入，继续内存浏览", unavailable);
            }
            page.dirtySlots = 0;
        }
        } catch (MapPersistence.LimitExceeded limit) {
            disabled.set(true); MapPersistence.failed(MapTileCacheResult.SaveState.ENCODING_LIMIT); MapCacheStorageNotice.unavailable(world);
        }
    }
    @Override public void close() throws IOException {
        try {
            var iterator = pages.values().iterator();
            while (iterator.hasNext()) { var page = iterator.next(); iterator.remove(); flush(page); }
        } finally { pages.clear(); }
    }
}
