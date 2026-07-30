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
import java.util.Objects;

public final class WorldMapCacheCleaner {
    private final DeleteOperation deleteOperation;

    public WorldMapCacheCleaner() {
        this(Files::delete);
    }

    WorldMapCacheCleaner(DeleteOperation deleteOperation) {
        this.deleteOperation = Objects.requireNonNull(deleteOperation, "deleteOperation");
    }

    public Summary inspect(WorldInfo world) throws IOException {
        Path worldRoot = protectedWorldRoot(world);
        if (!Files.exists(worldRoot)) {
            return new Summary(0, 0);
        }
        long[] files = {0};
        long[] bytes = {0};
        Files.walkFileTree(worldRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                files[0]++;
                bytes[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return new Summary(files[0], bytes[0]);
    }

    public ClearResult clear(WorldInfo world) throws IOException {
        Path worldRoot = protectedWorldRoot(world);
        if (!Files.exists(worldRoot)) {
            return new ClearResult(0, 0, 0, null);
        }
        MutableClearResult result = new MutableClearResult();
        Files.walkFileTree(worldRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                try {
                    deleteOperation.delete(file);
                    result.deletedFiles++;
                    result.deletedBytes += attributes.size();
                } catch (IOException failure) {
                    result.recordFailure(failure);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                result.recordFailure(failure);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                if (failure != null) {
                    result.recordFailure(failure);
                }
                try {
                    deleteOperation.delete(directory);
                } catch (IOException deleteFailure) {
                    result.recordFailure(deleteFailure);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result.toResult();
    }

    private static Path protectedWorldRoot(WorldInfo world) throws IOException {
        Objects.requireNonNull(world, "world");
        Path cacheRoot = PortablePaths.cacheDirectory().toAbsolutePath().normalize();
        Path worldRoot = WorldCachePaths.worldDirectory(world).toAbsolutePath().normalize();
        if (!worldRoot.startsWith(cacheRoot) || worldRoot.equals(cacheRoot)) {
            throw new IOException("refusing to clear path outside the portable cache: " + worldRoot);
        }
        return worldRoot;
    }

    public record Summary(long fileCount, long bytes) {
        public Summary {
            if (fileCount < 0 || bytes < 0) {
                throw new IllegalArgumentException("cache summary values must not be negative");
            }
        }
    }

    public record ClearResult(
            long deletedFiles,
            long deletedBytes,
            long failedEntries,
            String firstFailure) {
        public ClearResult {
            if (deletedFiles < 0 || deletedBytes < 0 || failedEntries < 0) {
                throw new IllegalArgumentException("cache clear values must not be negative");
            }
            if (failedEntries == 0 && firstFailure != null) {
                throw new IllegalArgumentException("successful clear cannot contain a failure");
            }
        }

        public boolean changed() {
            return deletedFiles > 0;
        }

        public boolean hasFailures() {
            return failedEntries > 0;
        }
    }

    @FunctionalInterface
    interface DeleteOperation {
        void delete(Path path) throws IOException;
    }

    private static final class MutableClearResult {
        private long deletedFiles;
        private long deletedBytes;
        private long failedEntries;
        private String firstFailure;

        private void recordFailure(IOException failure) {
            failedEntries++;
            if (firstFailure == null) {
                String message = failure.getMessage();
                firstFailure = message == null || message.isBlank()
                        ? failure.getClass().getSimpleName()
                        : message;
            }
        }

        private ClearResult toResult() {
            return new ClearResult(
                    deletedFiles,
                    deletedBytes,
                    failedEntries,
                    firstFailure);
        }
    }
}
