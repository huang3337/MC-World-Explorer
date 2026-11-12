package com.mcworldexplorer.region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RegionHeaderIndexTest {
    @TempDir Path temp;

    @Test void indexesLocationsWithoutReadingInvalidBody() throws Exception {
        Path path = temp.resolve("r.-1.-1.mca");
        byte[] data = new byte[12288];
        ByteBuffer.wrap(data).putInt(0, (2 << 8) | 1).putInt(4, (1 << 8) | 1)
                .putInt(8, (9 << 8) | 1).putInt(12, 2 << 8);
        Files.write(path, data); // 正文完全无效，索引仍只判断头表位置。
        var index = RegionHeaderIndex.read(path);
        assertEquals(RegionHeaderIndex.State.PRESENT, index.state(0, 0));
        for (int x = 1; x <= 3; x++) assertEquals(RegionHeaderIndex.State.INVALID, index.state(x, 0));
        assertEquals(RegionHeaderIndex.State.MISSING, index.state(4, 0));
        assertArrayEquals(new int[]{0, 1, 2, 3}, index.occupiedSlots());
        index.occupiedSlots()[0] = 999;
        assertEquals(0, index.occupiedSlots()[0]);
        assertThrows(IOException.class, () -> {
            try (var reader = new RegionFileReader(path)) { reader.readChunk(0, 0); }
        });
        assertThrows(IllegalArgumentException.class, () -> index.state(-1, 0));
    }

    @Test void detectsHeaderChangesWithPreservedSizeAndTimestamp() throws Exception {
        Path path = temp.resolve("r.0.0.mca");
        byte[] bytes = new byte[8192];
        Files.write(path, bytes);
        var index = RegionHeaderIndex.read(path);
        var modified = Files.getLastModifiedTime(path);
        bytes[4096] = 1;
        Files.write(path, bytes);
        Files.setLastModifiedTime(path, modified);
        assertThrows(IOException.class, index::verifyUnchanged);
        assertNotEquals(index.signature(), RegionHeaderIndex.read(path).signature());
    }

    @Test void rejectsTruncatedHeadersAndDetectsDeletion() throws Exception {
        Path path = temp.resolve("r.0.0.mca");
        Files.write(path, new byte[8191]);
        assertThrows(IOException.class, () -> RegionHeaderIndex.read(path));
        Files.write(path, new byte[8192]);
        var index = RegionHeaderIndex.read(path);
        Files.delete(path);
        assertThrows(IOException.class, index::verifyUnchanged);
    }
    @Test void signatureOnlyReadRetainsHashChecksAndDistinguishesUnverifiedEmptyFiles() throws Exception {
        Path file = temp.resolve("r.0.0.mca");
        Files.createFile(file);
        var empty = RegionHeaderIndex.read(file);
        assertEquals(0, empty.signature().size());
        assertEquals(0, empty.occupiedSlots().length);
        empty.verifyUnchanged();
        Files.write(file, new byte[8191]);
        assertThrows(IOException.class, empty::verifyUnchanged);
        assertThrows(IOException.class, () -> RegionHeaderIndex.readSignature(file));
        byte[] header = new byte[8192]; Files.write(file, header);
        var before = RegionHeaderIndex.readSignature(file);
        assertEquals(before, RegionHeaderIndex.read(file).signature());
        assertEquals(0, RegionHeaderIndex.read(file).occupiedSlots().length);
        var time = Files.getLastModifiedTime(file);
        header[4096] = 1; Files.write(file, header); Files.setLastModifiedTime(file, time);
        assertNotEquals(before, RegionHeaderIndex.readSignature(file));
        assertEquals(RegionHeaderIndex.read(file).signature(), RegionHeaderIndex.readSignature(file));
    }
}
