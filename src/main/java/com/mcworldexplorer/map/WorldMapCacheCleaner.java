package com.mcworldexplorer.map;

import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class WorldMapCacheCleaner {
    public void clear(WorldInfo world) throws IOException {
        Path cacheRoot = PortablePaths.cacheDirectory().toAbsolutePath().normalize();
        Path worldRoot = WorldCachePaths.worldDirectory(world).toAbsolutePath().normalize();
        if (!worldRoot.startsWith(cacheRoot) || worldRoot.equals(cacheRoot)) {
            throw new IOException("refusing to clear path outside the portable cache: " + worldRoot);
        }
        if (!Files.exists(worldRoot)) {
            return;
        }
        Files.walkFileTree(worldRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
