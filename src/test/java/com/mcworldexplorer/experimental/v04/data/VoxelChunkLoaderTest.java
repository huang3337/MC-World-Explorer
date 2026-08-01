package com.mcworldexplorer.experimental.v04.data;

import com.mcworldexplorer.experimental.v04.V04Arguments;
import com.mcworldexplorer.region.ChunkCompression;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelChunkLoaderTest {
    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = SECTOR_BYTES * 2;

    @TempDir
    Path tempDir;

    @Test
    void loadsNegativeTargetAndTreatsMissingNeighborsAsAir() throws Exception {
        Path world = Files.createDirectory(tempDir.resolve("world"));
        Path region = Files.createDirectories(world.resolve("region"));
        writeSingleChunk(region.resolve("r.-1.-1.mca"), 31, 31, chunk("minecraft:stone"));
        V04Arguments arguments = V04Arguments.parse(new String[]{
                "--world", world.toString(), "--dimension", "0",
                "--chunk-x", "-1", "--chunk-z", "-1"});

        VoxelChunkLoadResult result = new VoxelChunkLoader().loadMeasured(arguments);
        VoxelChunkNeighborhood loaded = result.neighborhood();

        assertEquals("minecraft:stone", loaded.blockState(-16, 0, -16).name());
        assertEquals(VoxelBlockState.AIR, loaded.blockState(-17, 0, -16));
        assertTrue(loaded.boundaryComplete());
        assertTrue(result.regionReadNanos() >= 0);
        assertTrue(result.parseNanos() >= 0);
    }

    @Test
    void reportsDamagedNeighborButKeepsTarget() throws Exception {
        Path world = Files.createDirectory(tempDir.resolve("world"));
        Path region = Files.createDirectories(world.resolve("region"));
        writeTwoChunks(
                region.resolve("r.0.0.mca"),
                chunk("minecraft:stone"),
                new byte[]{1, 2, 3});
        V04Arguments arguments = V04Arguments.parse(new String[]{
                "--world", world.toString(), "--dimension", "minecraft:overworld",
                "--chunk-x", "0", "--chunk-z", "0"});

        VoxelChunkNeighborhood loaded = new VoxelChunkLoader().load(arguments);

        assertEquals("minecraft:stone", loaded.blockState(0, 0, 0).name());
        assertFalse(loaded.boundaryComplete());
        assertEquals(1, loaded.warnings().size());
        assertEquals(VoxelChunkNeighborhood.Direction.EAST,
                loaded.warnings().get(0).direction());
        assertEquals(VoxelBlockState.AIR, loaded.blockState(16, 0, 0));
    }

    @Test
    void missingTargetIsFatal() throws Exception {
        Path world = Files.createDirectory(tempDir.resolve("world"));
        Files.createDirectory(world.resolve("region"));
        V04Arguments arguments = V04Arguments.parse(new String[]{
                "--world", world.toString(), "--dimension", "0",
                "--chunk-x", "0", "--chunk-z", "0"});

        VoxelLoadException failure = assertThrows(
                VoxelLoadException.class,
                () -> new VoxelChunkLoader().load(arguments));

        assertEquals(VoxelLoadException.Reason.TARGET_CHUNK_MISSING, failure.reason());
    }

    private static byte[] chunk(String name) throws IOException {
        ListBinaryTag palette = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                .add(CompoundBinaryTag.builder().putString("Name", name).build())
                .build();
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .put("block_states", CompoundBinaryTag.builder().put("palette", palette).build())
                .build();
        ListBinaryTag sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(section).build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("sections", sections).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(root, output, BinaryTagIO.Compression.NONE);
        return output.toByteArray();
    }

    private static void writeSingleChunk(
            Path path,
            int localX,
            int localZ,
            byte[] nbt) throws IOException {
        ByteBuffer file = ByteBuffer.allocate(HEADER_BYTES + SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
        putLocation(file, localX, localZ, 2, 1);
        putChunk(file, 2, ChunkCompression.UNCOMPRESSED.id(), nbt);
        Files.write(path, file.array());
    }

    private static void writeTwoChunks(Path path, byte[] target, byte[] damaged) throws IOException {
        ByteBuffer file = ByteBuffer.allocate(HEADER_BYTES + SECTOR_BYTES * 2).order(ByteOrder.BIG_ENDIAN);
        putLocation(file, 0, 0, 2, 1);
        putLocation(file, 1, 0, 3, 1);
        putChunk(file, 2, ChunkCompression.UNCOMPRESSED.id(), target);
        putChunk(file, 3, ChunkCompression.GZIP.id(), damaged);
        Files.write(path, file.array());
    }

    private static void putLocation(
            ByteBuffer file,
            int localX,
            int localZ,
            int sectorOffset,
            int sectorCount) {
        int position = (localX + localZ * 32) * Integer.BYTES;
        file.put(position, (byte) (sectorOffset >>> 16));
        file.put(position + 1, (byte) (sectorOffset >>> 8));
        file.put(position + 2, (byte) sectorOffset);
        file.put(position + 3, (byte) sectorCount);
    }

    private static void putChunk(
            ByteBuffer file,
            int sectorOffset,
            int compression,
            byte[] payload) {
        file.position(sectorOffset * SECTOR_BYTES);
        file.putInt(1 + payload.length);
        file.put((byte) compression);
        file.put(payload);
    }
}
