package com.mcworldexplorer.preview;

import com.mcworldexplorer.world.WorldInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WorldDimensionDiscovery {
    private WorldDimensionDiscovery() {
    }

    public static List<WorldDimension> discover(WorldInfo world) throws IOException {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }

        Path worldFolder = world.getFolderPath();
        List<WorldDimension> dimensions = new ArrayList<>();
        dimensions.add(WorldDimension.overworld(worldFolder));

        if (Files.isDirectory(worldFolder.resolve("DIM-1").resolve("region"))) {
            dimensions.add(WorldDimension.nether(worldFolder));
        }
        if (Files.isDirectory(worldFolder.resolve("DIM1").resolve("region"))) {
            dimensions.add(WorldDimension.end(worldFolder));
        }

        List<WorldDimension> modDimensions = new ArrayList<>(discoverModDimensions(worldFolder));
        modDimensions.sort(Comparator.comparing(WorldDimension::id));
        dimensions.addAll(modDimensions);
        return List.copyOf(dimensions);
    }

    private static List<WorldDimension> discoverModDimensions(Path worldFolder) throws IOException {
        Path dimensionsRoot = worldFolder.resolve("dimensions").normalize();
        if (!Files.isDirectory(dimensionsRoot)) {
            return List.of();
        }

        List<WorldDimension> dimensions = new ArrayList<>();
        Files.walkFileTree(dimensionsRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if ("region".equals(directory.getFileName().toString())) {
                    createModDimension(dimensionsRoot, directory).ifPresent(dimensions::add);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if ("entities".equals(directory.getFileName().toString())
                        || "poi".equals(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return dimensions;
    }

    private static java.util.Optional<WorldDimension> createModDimension(
            Path dimensionsRoot,
            Path regionDirectory) {
        Path normalizedRegion = regionDirectory.normalize();
        if (!normalizedRegion.startsWith(dimensionsRoot)) {
            return java.util.Optional.empty();
        }
        Path relative = dimensionsRoot.relativize(normalizedRegion.getParent());
        if (relative.getNameCount() < 2) {
            return java.util.Optional.empty();
        }

        String namespace = relative.getName(0).toString();
        StringBuilder dimensionPath = new StringBuilder();
        for (int i = 1; i < relative.getNameCount(); i++) {
            if (dimensionPath.length() > 0) {
                dimensionPath.append('/');
            }
            dimensionPath.append(relative.getName(i));
        }
        if (namespace.isBlank() || dimensionPath.isEmpty()) {
            return java.util.Optional.empty();
        }
        String id = namespace + ":" + dimensionPath;
        return java.util.Optional.of(new WorldDimension(
                id,
                dimensionPath.toString().replace('_', ' '),
                normalizedRegion,
                DimensionKind.MOD));
    }
}
