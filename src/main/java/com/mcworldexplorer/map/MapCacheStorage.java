package com.mcworldexplorer.map;

import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Shared commit lock and conservative quota accounting for derived 2D files. */
final class MapCacheStorage {
    static final Object LOCK = new Object();
    static final long WORLD_LIMIT = 512L << 20, APP_LIMIT = 2L << 30;
    private static Path scannedRoot;
    private static final Map<Path, Long> usage = new HashMap<>();
    private static long total;
    private MapCacheStorage() { }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static Path root(WorldInfo world) { return WorldCachePaths.worldDirectory(world).resolve("map-v075"); }
    static String generation(WorldInfo world) throws IOException {
        Path p = root(world).resolve("generation");
        try {
            if (Files.size(p) > 128) throw new IOException("Invalid cache generation token");
            return Files.readString(p);
        } catch (NoSuchFileException e) { return "initial"; }
    }
    static void force(WorldInfo world) throws IOException {
        synchronized (LOCK) {
            Path p = root(world).resolve("generation");
            atomic(p, UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            scannedRoot = null;
            MapCacheStorageNotice.clear();
        }
    }
    static void atomic(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(".pending-" + UUID.randomUUID() + ".tmp");
        // CREATE_NEW acquires ownership before cleanup is armed. A name collision must
        // never truncate or delete another writer's file.
        OutputStream created = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            try (created) { created.write(data); }
            try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    // Call under LOCK. Size accounting is conservative after external removals; refresh rescans.
    static boolean write(WorldInfo world, Map<Path, byte[]> files) throws IOException {
        return write(world, files, WORLD_LIMIT, APP_LIMIT);
    }
    static boolean write(WorldInfo world, Map<Path, byte[]> files, long worldLimit, long appLimit) throws IOException {
        Path app = PortablePaths.cacheDirectory().toAbsolutePath().normalize();
        Path wr = WorldCachePaths.worldDirectory(world).toAbsolutePath().normalize();
        if (!app.equals(scannedRoot)) {
            usage.clear(); total = 0;
            if (Files.exists(app)) try (var walk = Files.walk(app)) {
                var iterator = walk.filter(Files::isRegularFile).iterator();
                while (iterator.hasNext()) {
                    Path p = iterator.next();
                    Path rel = app.relativize(p);
                    if (rel.getNameCount() < 3 || !Set.of("map-v03", "map-v075").contains(rel.getName(1).toString())) continue;
                    long size = Files.size(p); total += size;
                    usage.merge(app.resolve(rel.getName(0)), size, Long::sum);
                }
            }
            scannedRoot = app;
        }
        long delta = 0;
        for (var e : files.entrySet()) {
            if (!e.getKey().toAbsolutePath().normalize().startsWith(wr)) throw new IOException("Cache escaped world root");
            delta += e.getValue().length - (Files.exists(e.getKey()) ? Files.size(e.getKey()) : 0);
        }
        if (total + delta > appLimit || usage.getOrDefault(wr, 0L) + delta > worldLimit) {
            MapCacheStorageNotice.limited(world);
            org.slf4j.LoggerFactory.getLogger(MapCacheStorage.class).warn("二维缓存达到容量上限，停止持久化；可清理当前世界缓存");
            return false;
        }
        try { for (var e : files.entrySet()) atomic(e.getKey(), e.getValue()); }
        catch (IOException failure) { scannedRoot = null; throw failure; }
        total += delta; usage.merge(wr, delta, Long::sum); return true;
    }
}
