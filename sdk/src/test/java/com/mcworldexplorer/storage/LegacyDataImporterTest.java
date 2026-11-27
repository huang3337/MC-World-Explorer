package com.mcworldexplorer.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LegacyDataImporterTest {

    @Test
    void copiesConfigAndCacheWithoutModifyingSource(@TempDir Path temp) throws Exception {
        Path legacy = Files.createDirectories(temp.resolve("old-mcwe"));
        Path current = Files.createDirectories(temp.resolve("new-mcwe"));

        Files.createDirectories(legacy.resolve("config/places"));
        Files.createDirectories(legacy.resolve("config/model-plugins/sample"));
        Files.createDirectories(legacy.resolve("cache/world-a/overworld"));
        Files.writeString(legacy.resolve("config/settings.properties"), "appearance=dark\n");
        Files.writeString(legacy.resolve("config/places/world-a.properties"), "0,0=Base\n");
        Files.writeString(legacy.resolve("config/model-plugins/sample/plugin.jar"), "jar-bytes");
        Files.writeString(legacy.resolve("cache/world-a/overworld/0_0.png"), "png-bytes");

        var result = LegacyDataImporter.importFrom(legacy, current, LegacyDataImporter.Options.ALL);

        assertEquals(3, result.configFilesCopied());
        assertEquals(1, result.cacheFilesCopied());
        assertEquals("appearance=dark\n", Files.readString(current.resolve("config/settings.properties")));
        assertEquals("0,0=Base\n", Files.readString(current.resolve("config/places/world-a.properties")));
        assertEquals("jar-bytes", Files.readString(current.resolve("config/model-plugins/sample/plugin.jar")));
        assertEquals("png-bytes", Files.readString(current.resolve("cache/world-a/overworld/0_0.png")));
        // Source files remain untouched.
        assertTrue(Files.isRegularFile(legacy.resolve("config/settings.properties")));
        assertTrue(Files.isRegularFile(legacy.resolve("cache/world-a/overworld/0_0.png")));
    }

    @Test
    void rejectsSelfParentChildAndEmptySourceDirectories(@TempDir Path temp) throws Exception {
        Path current = Files.createDirectories(temp.resolve("app"));
        Path child = Files.createDirectories(current.resolve("nested"));
        Path empty = Files.createDirectories(temp.resolve("empty-dir"));

        assertThrows(IOException.class, () -> LegacyDataImporter.importFrom(current, current, LegacyDataImporter.Options.ALL));
        assertThrows(IOException.class, () -> LegacyDataImporter.importFrom(temp, current, LegacyDataImporter.Options.ALL));
        assertThrows(IOException.class, () -> LegacyDataImporter.importFrom(child, current, LegacyDataImporter.Options.ALL));
        assertThrows(IOException.class, () -> LegacyDataImporter.importFrom(empty, current, LegacyDataImporter.Options.ALL));
    }

    @Test
    void rejectsSymbolicLinksDuringImport(@TempDir Path temp) throws Exception {
        Path legacy = Files.createDirectories(temp.resolve("old-mcwe"));
        Path current = Files.createDirectories(temp.resolve("new-mcwe"));
        Path outside = Files.writeString(temp.resolve("outside.txt"), "secret");
        Files.createDirectories(legacy.resolve("config/places"));

        Path symlink = legacy.resolve("config/places/link.properties");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "OS environment does not permit creating symbolic links without elevation");
        }

        assertThrows(IOException.class, () -> LegacyDataImporter.importFrom(legacy, current, LegacyDataImporter.Options.ALL));
    }

    @Test
    void detectsFirstRunOnlyForFreshPortableDirectories(@TempDir Path temp) throws Exception {
        Path freshPortable = Files.createDirectories(temp.resolve("portable"));
        assertTrue(PortableSettings.shouldShowFirstRunImport(freshPortable));

        Files.createDirectories(freshPortable.resolve("config"));
        Files.writeString(freshPortable.resolve("config/settings.properties"), "first_run_completed=true\n");
        assertFalse(PortableSettings.shouldShowFirstRunImport(freshPortable));

        Path devRoot = Files.createDirectories(temp.resolve("dev-repo"));
        Files.writeString(devRoot.resolve("build.gradle"), "");
        Files.writeString(devRoot.resolve("settings.gradle"), "");
        assertFalse(PortableSettings.shouldShowFirstRunImport(devRoot));
    }
}
