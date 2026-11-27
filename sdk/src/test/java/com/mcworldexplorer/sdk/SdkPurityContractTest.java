package com.mcworldexplorer.sdk;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test ensuring {@code :mc-world-explorer-sdk} remains completely free of
 * JavaFX, LWJGL, Ikonli, desktop UI, and experimental classes at both classpath and bytecode levels.
 */
class SdkPurityContractTest {
    private static final List<String> FORBIDDEN_REFS = List.of(
            "javafx/",
            "org/lwjgl/",
            "org/kordamp/ikonli/",
            "com/mcworldexplorer/ui/",
            "com/mcworldexplorer/update/",
            "com/mcworldexplorer/viewer/lwjgl/",
            "com/mcworldexplorer/voxel/render/lwjgl/",
            "com/mcworldexplorer/experimental/",
            "com/mcworldexplorer/App",
            "com/mcworldexplorer/Launcher"
    );

    @Test
    void sdkRuntimeClasspathExcludesJavaFxAndLwjgl() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("javafx.application.Application"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.lwjgl.glfw.GLFW"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.mcworldexplorer.App"));
    }

    @Test
    void sdkCompiledBytecodeContainsZeroUiOrOpenGlReferences() throws Exception {
        Path location = Path.of(WorldExplorerSdk.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> violations = new ArrayList<>();
        int scannedClasses = 0;

        if (Files.isDirectory(location)) {
            try (var stream = Files.walk(location)) {
                List<Path> classFiles = stream.filter(p -> p.toString().endsWith(".class")).toList();
                for (Path classFile : classFiles) {
                    scannedClasses++;
                    String raw = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                    for (String forbidden : FORBIDDEN_REFS) {
                        if (raw.contains(forbidden)) {
                            violations.add(location.relativize(classFile) + " -> " + forbidden);
                        }
                    }
                }
            }
        } else if (Files.isRegularFile(location) && location.toString().endsWith(".jar")) {
            try (JarFile jar = new JarFile(location.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    scannedClasses++;
                    String raw = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                    for (String forbidden : FORBIDDEN_REFS) {
                        if (raw.contains(forbidden)) {
                            violations.add(entry.getName() + " -> " + forbidden);
                        }
                    }
                }
            }
        } else {
            fail("Unexpected SDK code source location: " + location);
        }

        assertTrue(scannedClasses > 50, "Expected to scan compiled SDK classes, found " + scannedClasses);
        assertTrue(violations.isEmpty(), () -> "SDK bytecode contains forbidden UI/OpenGL references: " + violations);
    }

    @Test
    void sdkMavenPublicationDeclaresProjectGplV3License() throws Exception {
        Path sdkBuild = Path.of("sdk/build.gradle");
        assertTrue(Files.isRegularFile(sdkBuild));
        String script = Files.readString(sdkBuild, StandardCharsets.UTF_8);
        assertTrue(script.contains("GNU General Public License v3.0"));
        assertTrue(script.contains("https://www.gnu.org/licenses/gpl-3.0.html"));
        assertFalse(script.contains("MIT License"));
    }
}
