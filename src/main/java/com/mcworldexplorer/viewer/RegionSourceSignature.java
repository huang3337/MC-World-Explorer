package com.mcworldexplorer.viewer;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.preview.WorldDimensionDiscovery;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.world.WorldInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.TreeSet;

public record RegionSourceSignature(List<RegionFileStamp> files) {
    public RegionSourceSignature {
        files = files.stream().sorted().toList();
    }

    public static RegionSourceSignature capture(ViewerAreaKey key) throws IOException {
        Path world = key.world().toRealPath();
        WorldDimension dimension = WorldDimensionDiscovery.discover(new WorldInfo(world)).stream()
                .filter(candidate -> candidate.id().equals(key.dimensionId()))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "dimension was not discovered: " + key.dimensionId()));
        Path regionDirectory = dimension.regionDirectory().toRealPath();
        if (!regionDirectory.startsWith(world)) {
            throw new IOException("dimension Region directory is outside the world: " + regionDirectory);
        }
        return capture(regionDirectory, key.target());
    }

    static RegionSourceSignature capture(Path regionDirectory, ChunkRectangle target)
            throws IOException {
        Path normalizedDirectory = regionDirectory.toAbsolutePath().normalize();
        TreeSet<Path> paths = new TreeSet<>((left, right) ->
                left.toString().compareToIgnoreCase(right.toString()));
        for (ChunkCoordinate coordinate : target.expanded(1).coordinates()) {
            int regionX = Math.floorDiv(coordinate.x(), 32);
            int regionZ = Math.floorDiv(coordinate.z(), 32);
            paths.add(normalizedDirectory.resolve("r." + regionX + "." + regionZ + ".mca"));
        }
        java.util.ArrayList<RegionFileStamp> stamps = new java.util.ArrayList<>(paths.size());
        for (Path path : paths) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                stamps.add(new RegionFileStamp(path, false, -1, -1));
                continue;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            stamps.add(new RegionFileStamp(
                    path,
                    true,
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis()));
        }
        return new RegionSourceSignature(stamps);
    }
}
