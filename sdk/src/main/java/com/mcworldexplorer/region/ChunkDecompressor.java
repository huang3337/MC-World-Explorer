package com.mcworldexplorer.region;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class ChunkDecompressor {
    // Region scans decode many small chunks on the same worker. Returned chunks own their bytes.
    private static final ThreadLocal<byte[]> PREFIX = ThreadLocal.withInitial(() -> new byte[64 * 1024]);

    private ChunkDecompressor() {
    }

    static byte[] decompress(
            ChunkCompression compression,
            byte[] payload,
            int maximumOutputBytes,
            Path regionPath,
            int localChunkX,
            int localChunkZ) throws RegionReadException {
        try (InputStream input = decompressionStream(compression, payload)) {
            if (compression == ChunkCompression.UNCOMPRESSED && payload.length <= maximumOutputBytes) {
                return payload;
            }
            byte[] prefix = PREFIX.get();
            int capacity = Math.min(prefix.length, maximumOutputBytes);
            int used = input.readNBytes(prefix, 0, capacity);
            if (used < capacity) return Arrays.copyOf(prefix, used);
            byte[] tail = input.readNBytes(maximumOutputBytes - used);
            if (used + tail.length == maximumOutputBytes && input.read() != -1) {
                throw failure(RegionReadException.Reason.DECOMPRESSED_SIZE_LIMIT, regionPath,
                        localChunkX, localChunkZ, "decompressed data exceeds " + maximumOutputBytes + " bytes");
            }
            byte[] result = Arrays.copyOf(prefix, used + tail.length);
            System.arraycopy(tail, 0, result, used, tail.length);
            return result;
        } catch (RegionReadException e) {
            throw e;
        } catch (IOException e) {
            throw new RegionReadException(
                    RegionReadException.Reason.DAMAGED_COMPRESSED_DATA,
                    regionPath,
                    localChunkX,
                    localChunkZ,
                    "failed to decompress " + compression,
                    e);
        }
    }

    private static InputStream decompressionStream(ChunkCompression compression, byte[] payload) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(payload);
        return switch (compression) {
            case GZIP -> new GZIPInputStream(input);
            case ZLIB -> new InflaterInputStream(input);
            case UNCOMPRESSED -> input;
            case LZ4 -> throw new IOException("LZ4 is not supported in the first implementation");
        };
    }

    private static RegionReadException failure(
            RegionReadException.Reason reason,
            Path regionPath,
            int localChunkX,
            int localChunkZ,
            String detail) {
        return new RegionReadException(reason, regionPath, localChunkX, localChunkZ, detail);
    }
}
