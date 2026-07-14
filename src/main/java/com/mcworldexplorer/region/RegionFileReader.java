package com.mcworldexplorer.region;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;

public final class RegionFileReader implements AutoCloseable {
    static final int SECTOR_BYTES = 4096;
    static final int HEADER_BYTES = SECTOR_BYTES * 2;
    static final int MAX_DECOMPRESSED_CHUNK_BYTES = 64 * 1024 * 1024;

    private final Path path;
    private final FileChannel channel;
    private final ByteBuffer header;

    public RegionFileReader(Path path) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        if (path.getFileName() == null
                || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mca")) {
            throw failure(RegionReadException.Reason.INVALID_FILE_TYPE, -1, -1, "expected an .mca file");
        }

        this.channel = FileChannel.open(this.path, StandardOpenOption.READ);
        try {
            this.header = readHeader();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    public Optional<RegionChunkData> readChunk(int localChunkX, int localChunkZ) throws IOException {
        validateLocalCoordinate(localChunkX, localChunkZ);
        int index = localChunkX + localChunkZ * 32;
        int locationPosition = index * Integer.BYTES;
        int sectorOffset = (Byte.toUnsignedInt(header.get(locationPosition)) << 16)
                | (Byte.toUnsignedInt(header.get(locationPosition + 1)) << 8)
                | Byte.toUnsignedInt(header.get(locationPosition + 2));
        int sectorCount = Byte.toUnsignedInt(header.get(locationPosition + 3));
        long timestamp = Integer.toUnsignedLong(header.getInt(SECTOR_BYTES + locationPosition));

        if (sectorOffset == 0 && sectorCount == 0) {
            return Optional.empty();
        }
        if (sectorOffset < 2 || sectorCount == 0) {
            throw failure(
                    RegionReadException.Reason.INVALID_LOCATION,
                    localChunkX,
                    localChunkZ,
                    "sector offset=" + sectorOffset + ", count=" + sectorCount);
        }

        long chunkStart = (long) sectorOffset * SECTOR_BYTES;
        long allocatedBytes = (long) sectorCount * SECTOR_BYTES;
        long fileSize = channel.size();
        if (chunkStart > fileSize || allocatedBytes > fileSize - chunkStart) {
            throw failure(
                    RegionReadException.Reason.INVALID_LOCATION,
                    localChunkX,
                    localChunkZ,
                    "declared sectors exceed the file size");
        }

        ByteBuffer chunkHeader = ByteBuffer.allocate(Integer.BYTES + 1).order(ByteOrder.BIG_ENDIAN);
        readFully(chunkHeader, chunkStart, localChunkX, localChunkZ);
        chunkHeader.flip();
        int declaredLength = chunkHeader.getInt();
        if (declaredLength < 1
                || (long) Integer.BYTES + declaredLength > allocatedBytes
                || (long) Integer.BYTES + declaredLength > fileSize - chunkStart) {
            throw failure(
                    RegionReadException.Reason.INVALID_CHUNK_LENGTH,
                    localChunkX,
                    localChunkZ,
                    "declared length=" + declaredLength + ", allocated bytes=" + allocatedBytes);
        }

        int compressionByte = Byte.toUnsignedInt(chunkHeader.get());
        boolean external = (compressionByte & 0x80) != 0;
        int compressionId = compressionByte & 0x7F;
        if (external) {
            throw failure(
                    RegionReadException.Reason.EXTERNAL_CHUNK_UNSUPPORTED,
                    localChunkX,
                    localChunkZ,
                    "external .mcc chunks are not supported yet");
        }

        ChunkCompression compression = ChunkCompression.fromId(compressionId)
                .orElseThrow(() -> failure(
                        RegionReadException.Reason.UNSUPPORTED_COMPRESSION,
                        localChunkX,
                        localChunkZ,
                        "unknown compression id " + compressionId));
        if (!compression.isSupported()) {
            throw failure(
                    RegionReadException.Reason.UNSUPPORTED_COMPRESSION,
                    localChunkX,
                    localChunkZ,
                    compression + " is recognized but not supported yet");
        }

        int payloadLength = declaredLength - 1;
        ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        readFully(payload, chunkStart + Integer.BYTES + 1L, localChunkX, localChunkZ);

        byte[] nbtData = ChunkDecompressor.decompress(
                compression,
                payload.array(),
                MAX_DECOMPRESSED_CHUNK_BYTES,
                path,
                localChunkX,
                localChunkZ);
        return Optional.of(new RegionChunkData(
                localChunkX,
                localChunkZ,
                timestamp,
                compression,
                nbtData));
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private ByteBuffer readHeader() throws IOException {
        if (channel.size() < HEADER_BYTES) {
            throw failure(
                    RegionReadException.Reason.TRUNCATED_HEADER,
                    -1,
                    -1,
                    "file contains fewer than " + HEADER_BYTES + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        readFully(buffer, 0, -1, -1);
        buffer.flip();
        return buffer.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
    }

    private void readFully(
            ByteBuffer target,
            long position,
            int localChunkX,
            int localChunkZ) throws IOException {
        long currentPosition = position;
        while (target.hasRemaining()) {
            int read = channel.read(target, currentPosition);
            if (read < 0) {
                throw failure(
                        RegionReadException.Reason.TRUNCATED_CHUNK,
                        localChunkX,
                        localChunkZ,
                        "unexpected end of file");
            }
            if (read == 0) {
                continue;
            }
            currentPosition += read;
        }
    }

    private void validateLocalCoordinate(int localChunkX, int localChunkZ) throws RegionReadException {
        if (localChunkX < 0 || localChunkX >= 32 || localChunkZ < 0 || localChunkZ >= 32) {
            throw failure(
                    RegionReadException.Reason.LOCAL_COORDINATE_OUT_OF_RANGE,
                    localChunkX,
                    localChunkZ,
                    "local coordinates must be between 0 and 31");
        }
    }

    private RegionReadException failure(
            RegionReadException.Reason reason,
            int localChunkX,
            int localChunkZ,
            String detail) {
        return new RegionReadException(reason, path, localChunkX, localChunkZ, detail);
    }
}
