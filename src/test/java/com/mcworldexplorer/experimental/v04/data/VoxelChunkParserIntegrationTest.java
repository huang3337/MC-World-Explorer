package com.mcworldexplorer.experimental.v04.data;

import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VoxelChunkParserIntegrationTest {
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    private static final long MAX_CHUNK_NBT_BYTES = 64L * 1024 * 1024;
    private static final BinaryTagIO.Reader NBT_READER = BinaryTagIO.reader(MAX_CHUNK_NBT_BYTES);
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    @Test
    void crossChecksConfiguredV04ChunksAgainstIndependentRawDecoder() throws Exception {
        String configuredWorld = System.getenv("MCWORLD_V04_TEST_WORLD");
        assumeTrue(configuredWorld != null && !configuredWorld.isBlank());

        Path world = Path.of(configuredWorld).toRealPath();
        Path regionDirectory = world.resolve("region").toRealPath();
        List<ChunkCoordinate> coordinates = configuredCoordinates();
        Map<Path, FileSnapshot> before = snapshotRegionFiles(regionDirectory, coordinates);
        VerificationStats stats = new VerificationStats();

        for (ChunkCoordinate coordinate : coordinates) {
            RegionChunkData data = readChunk(regionDirectory, coordinate.x(), coordinate.z())
                    .orElseThrow(() -> new AssertionError("Configured chunk is missing: " + coordinate));
            verifyChunk(data, coordinate.x(), coordinate.z(), stats);
        }

        assertEquals(before, snapshotRegionFiles(regionDirectory, coordinates),
                "Region files changed during V0.4 parser verification");
        assertEquals(coordinates.size(), stats.chunks);
        assertTrue(stats.sections > 0);
        assertTrue(stats.blocksCompared > 0);
        System.out.printf("V0.4 configured chunk cross-check: %s%n", stats.summary());
    }

    @Test
    void crossChecksOnePaletteChunkPerRealWorldAcrossVersions() throws Exception {
        String versionsDirectory = System.getenv("MCWORLD_TEST_VERSIONS_DIR");
        assumeTrue(versionsDirectory != null && !versionsDirectory.isBlank());

        List<Path> worlds;
        try (Stream<Path> paths = Files.walk(Path.of(versionsDirectory))) {
            worlds = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("level.dat"))
                    .map(Path::getParent)
                    .distinct()
                    .sorted()
                    .toList();
        }
        assertFalse(worlds.isEmpty(), "No real worlds found for V0.4 parser verification");

        VerificationStats stats = new VerificationStats();
        int unsupportedWorlds = 0;
        int worldsWithoutChunks = 0;
        List<Path> unsupportedWorldPaths = new ArrayList<>();
        for (Path world : worlds) {
            WorldSampleResult result = verifyFirstOverworldChunk(world, stats);
            if (result == WorldSampleResult.UNSUPPORTED_LAYOUT) {
                unsupportedWorlds++;
                unsupportedWorldPaths.add(world);
            } else if (result == WorldSampleResult.NO_CHUNK) {
                worldsWithoutChunks++;
            }
        }

        assertTrue(stats.chunks > 0, "No palette-based real chunks were verified");
        System.out.printf(
                "V0.4 cross-version parser check: worlds=%d, unsupported=%d, noChunk=%d, %s%n",
                worlds.size(), unsupportedWorlds, worldsWithoutChunks, stats.summary());
        System.out.printf("V0.4 unsupported world layouts: %s%n", unsupportedWorldPaths);
        System.out.printf("V0.4 unreadable Region samples: %s%n", stats.unreadableRegionPaths);
    }

    private static WorldSampleResult verifyFirstOverworldChunk(
            Path world,
            VerificationStats stats) throws Exception {
        Path regionDirectory = world.resolve("region");
        if (!Files.isDirectory(regionDirectory)) {
            return WorldSampleResult.NO_CHUNK;
        }
        List<Path> regionFiles;
        try (Stream<Path> paths = Files.list(regionDirectory)) {
            regionFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> REGION_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator
                            .comparingInt((Path path) -> path.getFileName().toString().equals("r.0.0.mca") ? 0 : 1)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path regionFile : regionFiles) {
            FileSnapshot before = snapshot(regionFile);
            Optional<LocatedChunk> located;
            try {
                located = firstChunk(regionFile);
            } catch (IOException | RuntimeException e) {
                stats.unreadableRegionFiles++;
                stats.unreadableRegionPaths.add(regionFile);
                assertEquals(before, snapshot(regionFile), "Region file changed: " + regionFile);
                continue;
            }
            if (located.isEmpty()) {
                assertEquals(before, snapshot(regionFile), "Region file changed: " + regionFile);
                continue;
            }
            LocatedChunk chunk = located.orElseThrow();
            CompoundBinaryTag root;
            try {
                root = readRoot(chunk.data());
            } catch (IOException | RuntimeException e) {
                stats.unreadableRegionFiles++;
                stats.unreadableRegionPaths.add(regionFile);
                assertEquals(before, snapshot(regionFile), "Region file changed: " + regionFile);
                continue;
            }
            if (!isPaletteLayout(root)) {
                assertEquals(before, snapshot(regionFile), "Region file changed: " + regionFile);
                return WorldSampleResult.UNSUPPORTED_LAYOUT;
            }
            verifyChunk(chunk.data(), chunk.chunkX(), chunk.chunkZ(), stats);
            assertEquals(before, snapshot(regionFile), "Region file changed: " + regionFile);
            return WorldSampleResult.VERIFIED;
        }
        return WorldSampleResult.NO_CHUNK;
    }

    private static Optional<LocatedChunk> firstChunk(Path regionFile) throws IOException {
        Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
        assertTrue(matcher.matches(), "Invalid Region file name: " + regionFile);
        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        try (RegionFileReader reader = new RegionFileReader(regionFile)) {
            for (int localZ = 0; localZ < 32; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    Optional<RegionChunkData> data = reader.readChunk(localX, localZ);
                    if (data.isPresent()) {
                        return Optional.of(new LocatedChunk(
                                data.orElseThrow(),
                                regionX * 32 + localX,
                                regionZ * 32 + localZ));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<RegionChunkData> readChunk(
            Path regionDirectory,
            int chunkX,
            int chunkZ) throws IOException {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        Path regionFile = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
        try (RegionFileReader reader = new RegionFileReader(regionFile)) {
            return reader.readChunk(Math.floorMod(chunkX, 32), Math.floorMod(chunkZ, 32));
        }
    }

    private static void verifyChunk(
            RegionChunkData data,
            int chunkX,
            int chunkZ,
            VerificationStats stats) throws Exception {
        CompoundBinaryTag root = readRoot(data);
        assertTrue(isPaletteLayout(root), "Chunk does not use a supported palette layout");
        boolean modern = root.keySet().contains("sections");
        CompoundBinaryTag coordinateContainer = modern ? root : root.getCompound("Level");
        verifyStoredCoordinates(coordinateContainer, chunkX, chunkZ);

        if (root.keySet().contains("DataVersion")) {
            stats.dataVersions.merge(root.getInt("DataVersion"), 1, Integer::sum);
        }
        stats.layouts.merge(modern ? "modern-root" : "level-palette", 1, Integer::sum);

        VoxelChunk parsed = VoxelChunkParser.read(data, chunkX, chunkZ);
        ListBinaryTag rawSections = modern
                ? root.getList("sections", BinaryTagTypes.COMPOUND)
                : root.getCompound("Level").getList("Sections", BinaryTagTypes.COMPOUND);
        List<Integer> expectedSectionYs = new ArrayList<>();

        for (int sectionIndex = 0; sectionIndex < rawSections.size(); sectionIndex++) {
            CompoundBinaryTag section = rawSections.getCompound(sectionIndex);
            RawSection rawSection = rawSection(section, modern);
            if (rawSection == null) {
                continue;
            }
            int sectionY = section.getInt("Y");
            expectedSectionYs.add(sectionY);
            List<VoxelBlockState> palette = rawPalette(rawSection.palette());
            int bits = palette.size() == 1
                    ? 0
                    : Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
            String storage = storageLayout(bits, rawSection.data().length);
            stats.storageLayouts.merge(storage, 1, Integer::sum);

            for (int blockIndex = 0; blockIndex < BLOCKS_PER_SECTION; blockIndex++) {
                int paletteIndex = referencePaletteIndex(
                        blockIndex, bits, rawSection.data(), storage);
                assertTrue(paletteIndex >= 0 && paletteIndex < palette.size(),
                        "Raw palette index out of range at section " + sectionY
                                + ", block " + blockIndex + ": " + paletteIndex);
                int localX = blockIndex & 15;
                int localZ = (blockIndex >>> 4) & 15;
                int localY = (blockIndex >>> 8) & 15;
                int y = Math.addExact(Math.multiplyExact(sectionY, 16), localY);
                assertEquals(
                        palette.get(paletteIndex),
                        parsed.blockState(localX, y, localZ),
                        "Block mismatch at chunk " + chunkX + "," + chunkZ
                                + " local " + localX + "," + localY + "," + localZ
                                + " section " + sectionY);
                stats.blocksCompared++;
            }
            stats.sections++;
            stats.paletteEntries += palette.size();
        }

        expectedSectionYs.sort(Integer::compareTo);
        assertEquals(expectedSectionYs, parsed.sectionYs(),
                "Parsed Section set differs from raw NBT at chunk " + chunkX + "," + chunkZ);
        stats.chunks++;
    }

    private static RawSection rawSection(CompoundBinaryTag section, boolean modern) {
        if (modern) {
            CompoundBinaryTag states = section.getCompound("block_states");
            if (states.keySet().isEmpty()) {
                return null;
            }
            return new RawSection(
                    states.getList("palette", BinaryTagTypes.COMPOUND),
                    states.getLongArray("data"));
        }
        if (!section.keySet().contains("Palette")) {
            return null;
        }
        return new RawSection(
                section.getList("Palette", BinaryTagTypes.COMPOUND),
                section.getLongArray("BlockStates"));
    }

    private static List<VoxelBlockState> rawPalette(ListBinaryTag palette) {
        assertTrue(palette.size() > 0, "Raw palette is empty");
        List<VoxelBlockState> states = new ArrayList<>(palette.size());
        for (int index = 0; index < palette.size(); index++) {
            CompoundBinaryTag entry = palette.getCompound(index);
            String name = entry.getString("Name");
            assertFalse(name.isBlank(), "Raw palette entry has no Name at index " + index);
            Map<String, String> properties = new TreeMap<>();
            if (entry.keySet().contains("Properties")) {
                CompoundBinaryTag propertyTag = entry.getCompound("Properties");
                for (String key : propertyTag.keySet()) {
                    BinaryTag value = propertyTag.get(key);
                    assertEquals(BinaryTagTypes.STRING, value.type(),
                            "Raw property is not a string: " + key);
                    properties.put(key, propertyTag.getString(key));
                }
            }
            states.add(new VoxelBlockState(name, properties));
        }
        return states;
    }

    private static String storageLayout(int bits, int dataLength) {
        if (bits == 0) {
            return "single-palette";
        }
        int paddedLength = divideRoundUp(BLOCKS_PER_SECTION, Long.SIZE / bits);
        int compactLength = divideRoundUp(BLOCKS_PER_SECTION * bits, Long.SIZE);
        if (dataLength == paddedLength) {
            return "padded-" + bits;
        }
        if (dataLength == compactLength) {
            return "compact-" + bits;
        }
        throw new AssertionError(
                "Unexpected raw storage length " + dataLength
                        + ", expected " + paddedLength + " or " + compactLength);
    }

    private static int referencePaletteIndex(
            int blockIndex,
            int bits,
            long[] data,
            String storage) {
        if (bits == 0) {
            return 0;
        }
        long firstBit;
        if (storage.startsWith("padded-")) {
            int valuesPerLong = Long.SIZE / bits;
            firstBit = (long) (blockIndex / valuesPerLong) * Long.SIZE
                    + (long) (blockIndex % valuesPerLong) * bits;
        } else {
            firstBit = (long) blockIndex * bits;
        }
        int value = 0;
        for (int bit = 0; bit < bits; bit++) {
            long bitIndex = firstBit + bit;
            int longIndex = Math.toIntExact(bitIndex / Long.SIZE);
            int bitOffset = (int) (bitIndex % Long.SIZE);
            int bitValue = (int) ((data[longIndex] >>> bitOffset) & 1L);
            value |= bitValue << bit;
        }
        return value;
    }

    private static void verifyStoredCoordinates(
            CompoundBinaryTag container,
            int chunkX,
            int chunkZ) {
        boolean hasX = container.keySet().contains("xPos");
        boolean hasZ = container.keySet().contains("zPos");
        assertEquals(hasX, hasZ, "Chunk NBT contains only one stored coordinate");
        if (hasX) {
            assertEquals(chunkX, container.getInt("xPos"), "Stored xPos differs from Region slot");
            assertEquals(chunkZ, container.getInt("zPos"), "Stored zPos differs from Region slot");
        }
    }

    private static CompoundBinaryTag readRoot(RegionChunkData data) throws IOException {
        try (InputStream input = data.openNbtStream()) {
            return NBT_READER.read(input, BinaryTagIO.Compression.NONE);
        }
    }

    private static boolean isPaletteLayout(CompoundBinaryTag root) {
        if (root.keySet().contains("sections")) {
            return true;
        }
        ListBinaryTag sections = root.getCompound("Level")
                .getList("Sections", BinaryTagTypes.COMPOUND);
        for (int index = 0; index < sections.size(); index++) {
            if (sections.getCompound(index).keySet().contains("Palette")) {
                return true;
            }
        }
        return false;
    }

    private static List<ChunkCoordinate> configuredCoordinates() {
        String value = System.getenv("MCWORLD_V04_TEST_CHUNKS");
        if (value == null || value.isBlank()) {
            value = "0,30;1,29;1,30";
        }
        List<ChunkCoordinate> coordinates = new ArrayList<>();
        for (String pair : value.split(";")) {
            String[] parts = pair.trim().split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid MCWORLD_V04_TEST_CHUNKS entry: " + pair);
            }
            coordinates.add(new ChunkCoordinate(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim())));
        }
        return List.copyOf(coordinates);
    }

    private static Map<Path, FileSnapshot> snapshotRegionFiles(
            Path regionDirectory,
            List<ChunkCoordinate> coordinates) throws IOException {
        Map<Path, FileSnapshot> snapshots = new LinkedHashMap<>();
        for (ChunkCoordinate coordinate : coordinates) {
            int regionX = Math.floorDiv(coordinate.x(), 32);
            int regionZ = Math.floorDiv(coordinate.z(), 32);
            Path path = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
            snapshots.put(path, snapshot(path));
        }
        return snapshots;
    }

    private static FileSnapshot snapshot(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return new FileSnapshot(
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
                sha256(file));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(file);
             DigestInputStream digestInput = new DigestInputStream(input, digest)) {
            digestInput.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private enum WorldSampleResult {
        VERIFIED,
        UNSUPPORTED_LAYOUT,
        NO_CHUNK
    }

    private record ChunkCoordinate(int x, int z) {
    }

    private record LocatedChunk(RegionChunkData data, int chunkX, int chunkZ) {
    }

    private record RawSection(ListBinaryTag palette, long[] data) {
    }

    private record FileSnapshot(long size, long lastModifiedMillis, String sha256) {
    }

    private static final class VerificationStats {
        private int chunks;
        private int sections;
        private long blocksCompared;
        private long paletteEntries;
        private int unreadableRegionFiles;
        private final Map<String, Integer> layouts = new HashMap<>();
        private final Map<String, Integer> storageLayouts = new TreeMap<>();
        private final Map<Integer, Integer> dataVersions = new TreeMap<>();
        private final List<Path> unreadableRegionPaths = new ArrayList<>();

        private String summary() {
            return "chunks=" + chunks
                    + ", sections=" + sections
                    + ", blocks=" + blocksCompared
                    + ", paletteEntries=" + paletteEntries
                    + ", unreadableRegionFiles=" + unreadableRegionFiles
                    + ", layouts=" + layouts
                    + ", storage=" + storageLayouts
                    + ", dataVersions=" + dataVersions;
        }
    }
}
