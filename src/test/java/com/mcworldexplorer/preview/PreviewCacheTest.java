package com.mcworldexplorer.preview;

import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCacheTest {
    private static final String HOME_PROPERTY = "mcworldexplorer.home";

    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty(HOME_PROPERTY);

    @AfterEach
    void restoreHome() {
        if (originalHome == null) {
            System.clearProperty(HOME_PROPERTY);
        } else {
            System.setProperty(HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void storesPngAndMetadataUnderPortableCache() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        Path worldFolder = Files.createDirectory(tempDir.resolve("world-folder"));
        Files.writeString(worldFolder.resolve("level.dat"), "level");
        WorldInfo world = new WorldInfo(worldFolder);
        world.setLevelName("Test: World");
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        PreviewGenerationResult generation = new PreviewGenerationResult(
                image,
                new PreviewCenter(10, -20, PreviewCenterSource.WORLD_SPAWN),
                100,
                80,
                15,
                5,
                10_000,
                400);

        PreviewCacheResult stored = new PreviewCache().store(world, generation);

        assertTrue(stored.imagePath().startsWith(PortablePaths.cacheDirectory()));
        assertTrue(Files.isRegularFile(stored.imagePath()));
        assertTrue(Files.size(stored.imagePath()) > 0);
        assertTrue(Files.isRegularFile(stored.metadataPath()));
        String metadata = Files.readString(stored.metadataPath());
        assertTrue(metadata.contains("\"centerX\": 10"));
        assertTrue(metadata.contains("\"centerZ\": -20"));
        assertTrue(metadata.contains("\"worldPath\""));
        assertFalse(stored.imagePath().toString().contains("Test: World"));
    }

    @Test
    void usesStableWorldDirectoryAndReplacesCache() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        Path worldFolder = Files.createDirectory(tempDir.resolve("world"));
        WorldInfo world = new WorldInfo(worldFolder);
        BufferedImage firstImage = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        BufferedImage secondImage = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        secondImage.setRGB(0, 0, 0xFF123456);

        PreviewCache cache = new PreviewCache();
        PreviewCacheResult first = cache.store(world, result(firstImage));
        PreviewCacheResult second = cache.store(world, result(secondImage));

        assertEquals(first.imagePath(), second.imagePath());
        BufferedImage stored = javax.imageio.ImageIO.read(second.imagePath().toFile());
        assertEquals(0xFF123456, stored.getRGB(0, 0));
    }

    @Test
    void preventsDotWorldNameFromEscapingCacheDirectory() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        Path worldFolder = Files.createDirectory(tempDir.resolve("dot-world"));
        WorldInfo world = new WorldInfo(worldFolder);
        world.setLevelName("..");

        PreviewCacheResult stored = new PreviewCache().store(
                world,
                result(new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)));

        assertEquals(PortablePaths.cacheDirectory(), stored.imagePath().getParent().getParent().getParent());
        assertTrue(stored.imagePath().getParent().getParent().getFileName().toString().startsWith("world-"));
    }

    @Test
    void isolatesDimensionAndLayerCaches() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        Path worldFolder = Files.createDirectory(tempDir.resolve("layered-world"));
        Files.writeString(worldFolder.resolve("level.dat"), "level");
        Files.createDirectories(worldFolder.resolve("region"));
        Files.createDirectories(worldFolder.resolve("DIM-1/region"));
        WorldInfo world = new WorldInfo(worldFolder);
        PreviewGenerationResult generation = result(
                new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB));
        PreviewRequest overworldSurface = new PreviewRequest(
                WorldDimension.overworld(worldFolder),
                generation.center(),
                PreviewLayer.surfaceOverview());
        PreviewRequest netherBand = new PreviewRequest(
                WorldDimension.nether(worldFolder),
                generation.center(),
                PreviewLayer.heightBand(64, 95));
        PreviewCache cache = new PreviewCache();

        PreviewCacheResult surface = cache.store(world, overworldSurface, generation);
        PreviewCacheResult nether = cache.store(world, netherBand, generation);

        assertFalse(surface.imagePath().equals(nether.imagePath()));
        assertEquals("surface-overview.png", surface.imagePath().getFileName().toString());
        assertEquals("y-64-95.png", nether.imagePath().getFileName().toString());
        String metadata = Files.readString(nether.metadataPath());
        assertTrue(metadata.contains("\"dimensionId\": \"minecraft:the_nether\""));
        assertTrue(metadata.contains("\"layerType\": \"HEIGHT_BAND\""));
        assertEquals(surface, cache.findReusable(world, overworldSurface).orElseThrow());
        assertEquals(nether, cache.findReusable(world, netherBand).orElseThrow());
    }

    @Test
    void reusesMatchingCacheAndInvalidatesChangedSources() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        Path worldFolder = Files.createDirectory(tempDir.resolve("reusable-world"));
        Path levelDat = Files.writeString(worldFolder.resolve("level.dat"), "level");
        WorldInfo world = new WorldInfo(worldFolder);
        PreviewGenerationResult generation = result(
                new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB));
        PreviewCache cache = new PreviewCache();
        PreviewCacheResult stored = cache.store(world, generation);

        assertEquals(stored, cache.findReusable(world, generation.center()).orElseThrow());
        assertFalse(cache.findReusable(
                world,
                new PreviewCenter(1, 0, PreviewCenterSource.ORIGIN_FALLBACK)).isPresent());

        long newer = Files.getLastModifiedTime(stored.imagePath()).toMillis() + 2_000;
        Files.setLastModifiedTime(levelDat, FileTime.fromMillis(newer));
        assertFalse(cache.findReusable(world, generation.center()).isPresent());
    }

    @Test
    void invalidatesCacheWhenRegionSetChanges() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        Path worldFolder = Files.createDirectory(tempDir.resolve("region-state-world"));
        Files.writeString(worldFolder.resolve("level.dat"), "level");
        WorldInfo world = new WorldInfo(worldFolder);
        PreviewGenerationResult generation = result(
                new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB));
        PreviewCache cache = new PreviewCache();
        cache.store(world, generation);
        assertTrue(cache.findReusable(world, generation.center()).isPresent());

        Path regionDirectory = Files.createDirectory(worldFolder.resolve("region"));
        Files.write(regionDirectory.resolve("r.0.0.mca"), new byte[8192]);

        assertFalse(cache.findReusable(world, generation.center()).isPresent());
    }

    @Test
    void rejectsCorruptedPngEvenWhenMetadataStillMatches() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        Path worldFolder = Files.createDirectory(tempDir.resolve("corrupt-cache-world"));
        Files.writeString(worldFolder.resolve("level.dat"), "level");
        WorldInfo world = new WorldInfo(worldFolder);
        PreviewCache cache = new PreviewCache();
        PreviewGenerationResult generation = result(
                new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB));
        PreviewCacheResult stored = cache.store(world, generation);
        Files.writeString(stored.imagePath(), "not a png");

        assertFalse(cache.findReusable(world, generation.center()).isPresent());
    }

    @Test
    void rejectsCacheRequestsForRegionDirectoriesOutsideTheWorld() throws IOException {
        System.setProperty(HOME_PROPERTY, tempDir.toString());
        Path worldFolder = Files.createDirectory(tempDir.resolve("bounded-cache-world"));
        Path outsideRegion = Files.createDirectories(tempDir.resolve("outside/region"));
        WorldInfo world = new WorldInfo(worldFolder);
        PreviewGenerationResult generation = result(
                new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB));
        PreviewRequest request = new PreviewRequest(
                new WorldDimension("example:outside", "outside", outsideRegion, DimensionKind.MOD),
                generation.center(),
                PreviewLayer.surfaceOverview());
        PreviewCache cache = new PreviewCache();

        assertThrows(IllegalArgumentException.class,
                () -> cache.store(world, request, generation));
        assertThrows(IllegalArgumentException.class,
                () -> cache.findReusable(world, request));
    }

    private static PreviewGenerationResult result(BufferedImage image) {
        return new PreviewGenerationResult(
                image,
                new PreviewCenter(0, 0, PreviewCenterSource.ORIGIN_FALLBACK),
                1,
                1,
                0,
                0,
                1,
                0);
    }
}
