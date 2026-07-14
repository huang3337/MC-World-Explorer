package com.mcworldexplorer.region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionFileReaderTest {
    private static final byte[] NBT_BYTES = {10, 0, 0, 0};

    @TempDir
    Path tempDir;

    @Test
    void readsGzipZlibAndUncompressedChunks() throws IOException {
        for (ChunkCompression compression : new ChunkCompression[]{
                ChunkCompression.GZIP,
                ChunkCompression.ZLIB,
                ChunkCompression.UNCOMPRESSED}) {
            Path region = tempDir.resolve(compression.name().toLowerCase() + ".mca");
            writeRegion(region, 31, 31, 0xF1234567L, compression.id(), compress(compression, NBT_BYTES));

            try (RegionFileReader reader = new RegionFileReader(region)) {
                RegionChunkData chunk = reader.readChunk(31, 31).orElseThrow();

                assertEquals(31, chunk.getLocalChunkX());
                assertEquals(31, chunk.getLocalChunkZ());
                assertEquals(0xF1234567L, chunk.getTimestamp());
                assertEquals(compression, chunk.getCompression());
                assertArrayEquals(NBT_BYTES, chunk.openNbtStream().readAllBytes());
            }
        }
    }

    @Test
    void returnsEmptyForMissingChunk() throws IOException {
        Path region = tempDir.resolve("empty.mca");
        Files.write(region, new byte[RegionFileReader.HEADER_BYTES]);

        try (RegionFileReader reader = new RegionFileReader(region)) {
            Optional<RegionChunkData> chunk = reader.readChunk(0, 0);
            assertFalse(chunk.isPresent());
        }
    }

    @Test
    void rejectsInvalidFileTypeAndTruncatedHeader() throws IOException {
        Path wrongType = tempDir.resolve("region.dat");
        Files.write(wrongType, new byte[RegionFileReader.HEADER_BYTES]);
        assertReason(RegionReadException.Reason.INVALID_FILE_TYPE,
                () -> new RegionFileReader(wrongType));

        Path truncated = tempDir.resolve("truncated.mca");
        Files.write(truncated, new byte[RegionFileReader.HEADER_BYTES - 1]);
        assertReason(RegionReadException.Reason.TRUNCATED_HEADER,
                () -> new RegionFileReader(truncated));
    }

    @Test
    void rejectsOutOfRangeCoordinates() throws IOException {
        Path region = tempDir.resolve("coordinates.mca");
        Files.write(region, new byte[RegionFileReader.HEADER_BYTES]);

        try (RegionFileReader reader = new RegionFileReader(region)) {
            assertReason(RegionReadException.Reason.LOCAL_COORDINATE_OUT_OF_RANGE,
                    () -> reader.readChunk(-1, 0));
            assertReason(RegionReadException.Reason.LOCAL_COORDINATE_OUT_OF_RANGE,
                    () -> reader.readChunk(0, 32));
        }
    }

    @Test
    void rejectsInvalidLocationAndChunkLength() throws IOException {
        Path invalidLocation = tempDir.resolve("invalid-location.mca");
        ByteBuffer locationData = ByteBuffer.allocate(RegionFileReader.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        putLocation(locationData, 0, 0, 3, 1, 0);
        Files.write(invalidLocation, locationData.array());

        try (RegionFileReader reader = new RegionFileReader(invalidLocation)) {
            assertReason(RegionReadException.Reason.INVALID_LOCATION,
                    () -> reader.readChunk(0, 0));
        }

        Path invalidLength = tempDir.resolve("invalid-length.mca");
        writeRegion(invalidLength, 0, 0, 0, 3, NBT_BYTES);
        byte[] bytes = Files.readAllBytes(invalidLength);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                .putInt(RegionFileReader.HEADER_BYTES, RegionFileReader.SECTOR_BYTES);
        Files.write(invalidLength, bytes);

        try (RegionFileReader reader = new RegionFileReader(invalidLength)) {
            assertReason(RegionReadException.Reason.INVALID_CHUNK_LENGTH,
                    () -> reader.readChunk(0, 0));
        }
    }

    @Test
    void distinguishesExternalLz4AndUnknownCompression() throws IOException {
        assertUnsupportedCompression(4);
        assertUnsupportedCompression(99);

        Path external = tempDir.resolve("external.mca");
        writeRegion(external, 0, 0, 0, 0x80 | ChunkCompression.ZLIB.id(), new byte[0]);
        try (RegionFileReader reader = new RegionFileReader(external)) {
            assertReason(RegionReadException.Reason.EXTERNAL_CHUNK_UNSUPPORTED,
                    () -> reader.readChunk(0, 0));
        }
    }

    @Test
    void rejectsDamagedCompressedData() throws IOException {
        Path region = tempDir.resolve("damaged.mca");
        writeRegion(region, 0, 0, 0, ChunkCompression.GZIP.id(), new byte[]{1, 2, 3});

        try (RegionFileReader reader = new RegionFileReader(region)) {
            assertReason(RegionReadException.Reason.DAMAGED_COMPRESSED_DATA,
                    () -> reader.readChunk(0, 0));
        }
    }

    @Test
    void limitsDecompressedOutput() {
        assertReason(
                RegionReadException.Reason.DECOMPRESSED_SIZE_LIMIT,
                () -> ChunkDecompressor.decompress(
                        ChunkCompression.UNCOMPRESSED,
                        new byte[5],
                        4,
                        tempDir.resolve("limit.mca"),
                        0,
                        0));
    }

    private void assertUnsupportedCompression(int compressionId) throws IOException {
        Path region = tempDir.resolve("unsupported-" + compressionId + ".mca");
        writeRegion(region, 0, 0, 0, compressionId, new byte[0]);
        try (RegionFileReader reader = new RegionFileReader(region)) {
            assertReason(RegionReadException.Reason.UNSUPPORTED_COMPRESSION,
                    () -> reader.readChunk(0, 0));
        }
    }

    private static void writeRegion(
            Path path,
            int localX,
            int localZ,
            long timestamp,
            int compressionByte,
            byte[] payload) throws IOException {
        int declaredLength = 1 + payload.length;
        int sectorCount = Math.max(1,
                (Integer.BYTES + declaredLength + RegionFileReader.SECTOR_BYTES - 1)
                        / RegionFileReader.SECTOR_BYTES);
        ByteBuffer file = ByteBuffer.allocate(
                RegionFileReader.HEADER_BYTES + sectorCount * RegionFileReader.SECTOR_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        putLocation(file, localX, localZ, 2, sectorCount, timestamp);
        file.position(RegionFileReader.HEADER_BYTES);
        file.putInt(declaredLength);
        file.put((byte) compressionByte);
        file.put(payload);
        Files.write(path, file.array());
    }

    private static void putLocation(
            ByteBuffer file,
            int localX,
            int localZ,
            int sectorOffset,
            int sectorCount,
            long timestamp) {
        int position = (localX + localZ * 32) * Integer.BYTES;
        file.put(position, (byte) (sectorOffset >>> 16));
        file.put(position + 1, (byte) (sectorOffset >>> 8));
        file.put(position + 2, (byte) sectorOffset);
        file.put(position + 3, (byte) sectorCount);
        file.putInt(RegionFileReader.SECTOR_BYTES + position, (int) timestamp);
    }

    private static byte[] compress(ChunkCompression compression, byte[] data) throws IOException {
        if (compression == ChunkCompression.UNCOMPRESSED) {
            return data;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var compressor = compression == ChunkCompression.GZIP
                ? new GZIPOutputStream(output)
                : new DeflaterOutputStream(output)) {
            compressor.write(data);
        }
        return output.toByteArray();
    }

    private static void assertReason(
            RegionReadException.Reason expected,
            ThrowingOperation operation) {
        RegionReadException exception = assertThrows(RegionReadException.class, operation::run);
        assertEquals(expected, exception.getReason());
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws IOException;
    }
}
