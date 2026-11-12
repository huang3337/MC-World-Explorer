package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.SurfaceSampler;
import com.mcworldexplorer.region.RegionFileReader;
import net.kyori.adventure.nbt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class MapSamplingFixtures {
    static byte[] nbt(String block, int sectionY) throws IOException {
        var palette = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                .add(CompoundBinaryTag.builder().putString("Name", block).build()).build();
        var section = CompoundBinaryTag.builder().putInt("Y", sectionY)
                .put("block_states", CompoundBinaryTag.builder().put("palette", palette).build()).build();
        var root = CompoundBinaryTag.builder().put("sections",
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(section).build()).build();
        var output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(root, output, BinaryTagIO.Compression.NONE);
        return output.toByteArray();
    }

    static ChunkSurfaceSummary summary(String block, int sectionY) throws IOException {
        Path directory = Files.createTempDirectory(Path.of("build"), "p2-summary-test-");
        Path path = directory.resolve("r.0.0.mca");
        try {
            region(path, block, sectionY, 1, 0);
            try (var reader = new RegionFileReader(path)) {
                return new ChunkSurfaceSummary(new SurfaceSampler().sample(reader.readChunk(0, 0).orElseThrow()),
                        List.of(), "");
            }
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    static void region(Path path, String block, int sectionY, int timestamp, int... slots) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] payload = nbt(block, sectionY);
        ByteBuffer bytes = ByteBuffer.allocate((2 + slots.length) * 4096);
        for (int i = 0; i < slots.length; i++) {
            bytes.putInt(slots[i] * 4, ((2 + i) << 8) | 1);
            bytes.putInt(4096 + slots[i] * 4, timestamp);
            bytes.position((2 + i) * 4096);
            bytes.putInt(payload.length + 1).put((byte) 3).put(payload);
        }
        Files.write(path, bytes.array());
    }
}
