package com.mcworldexplorer.world;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WorldScannerTest {

    @Test
    public void testScanNonExistentDirectory() {
        Path fakePath = Paths.get("some/fake/path/that/does/not/exist");
        List<WorldInfo> worlds = WorldScanner.scanWorlds(fakePath);
        assertNotNull(worlds);
        assertTrue(worlds.isEmpty());
    }

    @Test
    public void testGetDefaultGameRootDoesNotCrash() {
        Path defaultRoot = WorldScanner.getDefaultGameRoot();
        // Since this runs on various environments, defaultRoot could be null or valid.
        // We just ensure it doesn't throw exceptions.
        if (defaultRoot != null) {
            assertTrue(defaultRoot.isAbsolute() || !defaultRoot.toString().isEmpty());
        }
    }
}
