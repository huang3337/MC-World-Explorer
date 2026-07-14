package com.mcworldexplorer.region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class ChunkDecompressor {
    private static final int BUFFER_SIZE = 8192;

    private ChunkDecompressor() {
    }

    static byte[] decompress(
            ChunkCompression compression,
            byte[] payload,
            int maximumOutputBytes,
            Path regionPath,
            int localChunkX,
            int localChunkZ) throws RegionReadException {
        try (InputStream input = decompressionStream(compression, payload);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(payload.length * 2, BUFFER_SIZE))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > maximumOutputBytes - total) {
                    throw failure(
                            RegionReadException.Reason.DECOMPRESSED_SIZE_LIMIT,
                            regionPath,
                            localChunkX,
                            localChunkZ,
                            "decompressed data exceeds " + maximumOutputBytes + " bytes");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
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
