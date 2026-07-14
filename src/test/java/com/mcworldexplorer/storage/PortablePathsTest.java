package com.mcworldexplorer.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortablePathsTest {
    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty(PortablePaths.APPLICATION_HOME_PROPERTY);

    @AfterEach
    void restoreApplicationHome() {
        if (originalHome == null) {
            System.clearProperty(PortablePaths.APPLICATION_HOME_PROPERTY);
        } else {
            System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void initializesApplicationRootAndCreatesLogsDirectory() throws IOException {
        Path configuredRoot = tempDir.resolve("portable-root");

        Path resolvedRoot = PortablePaths.initialize(configuredRoot);

        assertEquals(configuredRoot.toAbsolutePath(), resolvedRoot);
        assertTrue(Files.isDirectory(configuredRoot.resolve("logs")));
        assertEquals(configuredRoot.toAbsolutePath(), PortablePaths.applicationRoot());
        assertEquals(configuredRoot.toAbsolutePath().resolve("logs"), PortablePaths.logsDirectory());
        assertEquals(configuredRoot.toAbsolutePath().resolve("cache"), PortablePaths.cacheDirectory());
    }

    @Test
    void resolvesJpackageAppDirectoryFromMainJar() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle"));
        Files.createFile(tempDir.resolve("settings.gradle"));
        Path root = Files.createDirectories(tempDir.resolve("packaging/output/app-image"));
        Path appDirectory = Files.createDirectory(root.resolve("app"));
        Path mainJar = Files.createFile(appDirectory.resolve("MC-World-Explorer.jar"));

        Path resolvedRoot = PortablePaths.resolveApplicationRoot(
                mainJar,
                tempDir.resolve("unrelated-working-directory"),
                root.resolve("runtime"));

        assertEquals(root.toAbsolutePath(), resolvedRoot);
    }

    @Test
    void resolvesDevelopmentProjectFromCompiledClasses() throws IOException {
        Path projectRoot = Files.createDirectory(tempDir.resolve("project"));
        Files.createFile(projectRoot.resolve("build.gradle"));
        Files.createFile(projectRoot.resolve("settings.gradle"));
        Path classes = Files.createDirectories(projectRoot.resolve("build/classes/java/main"));

        Path resolvedRoot = PortablePaths.resolveApplicationRoot(classes, tempDir, null);

        assertEquals(projectRoot.toAbsolutePath(), resolvedRoot);
    }

    @Test
    void resolvesGradleDistributionFromLibJar() throws IOException {
        Path distributionRoot = Files.createDirectory(tempDir.resolve("distribution"));
        Path libDirectory = Files.createDirectory(distributionRoot.resolve("lib"));
        Path mainJar = Files.createFile(libDirectory.resolve("MC-World-Explorer.jar"));

        Path resolvedRoot = PortablePaths.resolveApplicationRoot(mainJar, tempDir, null);

        assertEquals(distributionRoot.toAbsolutePath(), resolvedRoot);
    }

    @Test
    void usesRuntimeFallbackOnlyForAnAppImage() throws IOException {
        Path appImage = Files.createDirectory(tempDir.resolve("runtime-fallback"));
        Files.createDirectory(appImage.resolve("app"));
        Path runtime = Files.createDirectory(appImage.resolve("runtime"));
        Path workingDirectory = tempDir.resolve("working-directory");

        assertEquals(appImage.toAbsolutePath(), PortablePaths.resolveApplicationRoot(
                null, workingDirectory, runtime));

        Path unrelatedRuntime = Files.createDirectories(tempDir.resolve("unrelated/runtime"));
        assertEquals(workingDirectory.toAbsolutePath(), PortablePaths.resolveApplicationRoot(
                null, workingDirectory, unrelatedRuntime));
    }

    @Test
    void rejectsLogsPathThatIsNotADirectory() throws IOException {
        Path configuredRoot = Files.createDirectory(tempDir.resolve("read-only-layout"));
        Files.writeString(configuredRoot.resolve("logs"), "occupied by a file");

        assertThrows(IOException.class, () -> PortablePaths.initialize(configuredRoot));
    }
}
