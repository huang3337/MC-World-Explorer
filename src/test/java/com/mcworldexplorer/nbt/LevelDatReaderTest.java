package com.mcworldexplorer.nbt;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class LevelDatReaderTest {

    @Test
    public void testReaderHandlesMissingDirectory() {
        Path fakePath = Paths.get("some/fake/path/that/does/not/exist");
        assertThrows(IOException.class, () -> {
            LevelDatReader.readLevelDat(fakePath);
        });
    }
}
