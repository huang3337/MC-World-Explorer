package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.data.ChunkRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RegionSourceSignatureTest {
    @TempDir
    Path tempDir;

    @Test
    void includesMissingNeighborRegionFilesAcrossBoundaries() throws IOException {
        RegionSourceSignature signature = RegionSourceSignature.capture(
                tempDir, ChunkRectangle.target(31, 0, 31, 0));

        assertEquals(4, signature.files().size());
        assertEquals(4, signature.files().stream().filter(stamp -> !stamp.exists()).count());
    }

    @Test
    void changesWhenMissingRegionAppearsOrContentChanges() throws IOException {
        ChunkRectangle target = ChunkRectangle.target(0, 0, 0, 0);
        RegionSourceSignature missing = RegionSourceSignature.capture(tempDir, target);
        Path region = tempDir.resolve("r.0.0.mca");
        Files.writeString(region, "first");
        RegionSourceSignature created = RegionSourceSignature.capture(tempDir, target);
        Files.writeString(region, "a longer second value");
        RegionSourceSignature changed = RegionSourceSignature.capture(tempDir, target);

        assertNotEquals(missing, created);
        assertNotEquals(created, changed);
    }
}
