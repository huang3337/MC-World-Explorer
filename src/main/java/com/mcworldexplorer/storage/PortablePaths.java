package com.mcworldexplorer.storage;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class PortablePaths {
    static final String APPLICATION_HOME_PROPERTY = "mcworldexplorer.home";
    private static final String LOGS_DIRECTORY_NAME = "logs";

    private PortablePaths() {
    }

    public static Path initialize() throws IOException {
        return initialize(detectedApplicationRoot());
    }

    static Path initialize(Path applicationRoot) throws IOException {
        Path root = normalized(applicationRoot);
        ensureWritableDirectory(root.resolve(LOGS_DIRECTORY_NAME));
        System.setProperty(APPLICATION_HOME_PROPERTY, root.toString());
        return root;
    }

    public static Path applicationRoot() {
        String initializedHome = System.getProperty(APPLICATION_HOME_PROPERTY);
        if (initializedHome != null && !initializedHome.isBlank()) {
            return normalized(Path.of(initializedHome));
        }
        return detectedApplicationRoot();
    }

    public static Path logsDirectory() {
        return applicationRoot().resolve(LOGS_DIRECTORY_NAME);
    }

    private static Path detectedApplicationRoot() {
        return resolveApplicationRoot(
                codeLocation(),
                pathProperty("user.dir"),
                pathProperty("java.home"));
    }

    static Path resolveApplicationRoot(
            Path codeLocation,
            Path workingDirectory,
            Path javaHome) {
        Path codeRoot = rootFromCodeLocation(codeLocation);
        if (codeRoot != null) {
            return codeRoot;
        }

        Path projectRoot = findProjectRoot(workingDirectory);
        if (projectRoot != null) {
            return projectRoot;
        }

        Path runtimeRoot = rootFromRuntime(javaHome);
        if (runtimeRoot != null) {
            return runtimeRoot;
        }

        if (workingDirectory == null) {
            throw new IllegalStateException("Cannot determine the application directory");
        }
        return normalized(workingDirectory);
    }

    static void ensureWritableDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path probe = null;
        try {
            probe = Files.createTempFile(directory, ".mcwe-write-test-", ".tmp");
        } finally {
            if (probe != null) {
                Files.deleteIfExists(probe);
            }
        }
    }

    private static Path rootFromCodeLocation(Path codeLocation) {
        if (codeLocation == null) {
            return null;
        }

        Path normalizedLocation = normalized(codeLocation);
        Path fileName = normalizedLocation.getFileName();
        if (fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            Path container = normalizedLocation.getParent();
            if (container == null) {
                return null;
            }

            String containerName = container.getFileName() == null ? "" : container.getFileName().toString();
            if (containerName.equalsIgnoreCase("app") || containerName.equalsIgnoreCase("lib")) {
                Path root = container.getParent();
                if (root != null) {
                    return root;
                }
            }
            return container;
        }

        return findProjectRoot(normalizedLocation);
    }

    private static Path rootFromRuntime(Path javaHome) {
        if (javaHome == null) {
            return null;
        }
        Path normalizedJavaHome = normalized(javaHome);
        Path fileName = normalizedJavaHome.getFileName();
        if (fileName == null || !fileName.toString().equalsIgnoreCase("runtime")) {
            return null;
        }
        Path root = normalizedJavaHome.getParent();
        if (root == null || !Files.isDirectory(root.resolve("app"))) {
            return null;
        }
        return root;
    }

    private static Path findProjectRoot(Path start) {
        if (start == null) {
            return null;
        }
        Path current = normalized(start);
        if (!Files.isDirectory(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (Files.isRegularFile(current.resolve("build.gradle"))
                    && Files.isRegularFile(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path codeLocation() {
        try {
            URL location = PortablePaths.class.getProtectionDomain().getCodeSource().getLocation();
            return Path.of(location.toURI());
        } catch (NullPointerException | SecurityException | URISyntaxException e) {
            return null;
        }
    }

    private static Path pathProperty(String propertyName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value);
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
