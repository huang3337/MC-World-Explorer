package com.mcworldexplorer.world;

import com.mcworldexplorer.nbt.LevelDatReader;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorldScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldScanner.class);

    public record ScanResult(Map<String, List<WorldInfo>> groups, Set<Path> failedPaths) {
        /** Retain old entries only where this scan could not establish the contents. */
        public Map<String, List<WorldInfo>> withLastReadable(Map<String, List<WorldInfo>> previous) {
            if (failedPaths.isEmpty() || previous.isEmpty()) return groups;
            Map<String, List<WorldInfo>> merged = new LinkedHashMap<>();
            Set<Path> present = new HashSet<>();
            groups.forEach((group, worlds) -> {
                merged.put(group, new ArrayList<>(worlds));
                worlds.forEach(world -> present.add(world.getFolderPath().toAbsolutePath().normalize()));
            });
            previous.forEach((group, worlds) -> {
                for (WorldInfo world : worlds) {
                    Path path = world.getFolderPath().toAbsolutePath().normalize();
                    if (!present.contains(path) && isWithinFailure(path)) {
                        merged.computeIfAbsent(group, key -> new ArrayList<>()).add(world);
                    }
                }
            });
            return merged;
        }

        private boolean isWithinFailure(Path path) {
            for (Path at = path; at != null; at = at.getParent()) {
                if (failedPaths.contains(at)) return true;
            }
            return false;
        }
    }

    private final Map<String, List<WorldInfo>> groups = new LinkedHashMap<>();
    private final Set<Path> failedPaths = new LinkedHashSet<>();
    private final Set<Path> knownWorlds = new HashSet<>();

    public static List<WorldInfo> scanWorlds(Path savesDir) {
        if (savesDir == null) return List.of();
        WorldScanner scanner = new WorldScanner();
        String group = groupName(savesDir, "Selected Saves");
        scanner.readSaves(savesDir, group);
        return scanner.groups.getOrDefault(group, List.of());
    }

    public static Map<String, List<WorldInfo>> scanGameRoot(Path rootDir) {
        WorldScanner scanner = new WorldScanner();
        if (rootDir != null) scanner.readGameRoot(rootDir);
        return scanner.groups;
    }

    public static Map<String, List<WorldInfo>> scanSelectedPath(Path selectedPath) {
        return scanLibrary(selectedPath).groups();
    }

    public static ScanResult scanLibrary(Path selectedPath) {
        return scanLibrary(selectedPath, Map.of());
    }

    public static ScanResult scanLibrary(Path selectedPath, Map<String, List<WorldInfo>> previous) {
        WorldScanner scanner = new WorldScanner();
        previous.values().forEach(worlds -> worlds.forEach(world ->
                scanner.knownWorlds.add(world.getFolderPath().toAbsolutePath().normalize())));
        if (selectedPath != null) scanner.readSelected(selectedPath);
        return new ScanResult(scanner.groups, scanner.failedPaths);
    }

    private void readSelected(Path root) {
        if (!isDirectory(root)) {
            failedPaths.add(root.toAbsolutePath().normalize());
            return;
        }
        if (hasLevelDat(root)) {
            readWorld(root, groupName(root, "Selected World"));
            return;
        }
        if (failedPaths.contains(root.toAbsolutePath().normalize())) return;
        String group = groupName(root, "Selected Saves");
        if (group.equalsIgnoreCase("saves")) {
            readSaves(root, group);
            return;
        }
        List<Path> children = directories(root);
        boolean containsWorlds = false;
        for (Path child : children) {
            if (hasLevelDat(child)) {
                containsWorlds = true;
                readWorld(child, group);
            }
        }
        if (containsWorlds) return;
        if (isDirectory(root.resolve("saves")) || isDirectory(root.resolve("versions"))) {
            readGameRoot(root);
        } else {
            readInstances(children);
        }
    }

    private void readGameRoot(Path root) {
        Path saves = root.resolve("saves");
        if (isDirectory(saves)) readSaves(saves, "默认存档 (Default)");
        Path versions = root.resolve("versions");
        if (isDirectory(versions)) readInstances(directories(versions));
    }

    private void readInstances(List<Path> instances) {
        for (Path instance : instances) {
            Path saves = instance.resolve("saves");
            if (isDirectory(saves)) readSaves(saves, groupName(instance, "Minecraft Instance"));
        }
    }

    private void readSaves(Path saves, String group) {
        for (Path child : directories(saves)) {
            if (hasLevelDat(child)) readWorld(child, group);
        }
    }

    private void readWorld(Path path, String group) {
        try {
            WorldInfo info = LevelDatReader.readLevelDat(path);
            if (info.isParsed()) {
                groups.computeIfAbsent(group, key -> new ArrayList<>()).add(info);
            } else {
                failedPaths.add(path.toAbsolutePath().normalize());
            }
        } catch (IOException | SecurityException failure) {
            recordFailure(path, failure);
        }
    }

    private List<Path> directories(Path parent) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                if (isDirectory(entry)) children.add(entry);
            }
        } catch (IOException | DirectoryIteratorException | SecurityException failure) {
            recordFailure(parent, failure);
        }
        return children;
    }

    private boolean isDirectory(Path path) {
        BasicFileAttributes attributes = attributes(path, path);
        return attributes != null && attributes.isDirectory();
    }

    private boolean hasLevelDat(Path world) {
        BasicFileAttributes attributes = attributes(world.resolve("level.dat"), world);
        if (attributes == null) {
            // A known world folder can temporarily lack level.dat during a save.
            Path path = world.toAbsolutePath().normalize();
            if (knownWorlds.contains(path)) failedPaths.add(path);
            return false;
        }
        if (attributes.isRegularFile()) return true;
        failedPaths.add(world.toAbsolutePath().normalize());
        return false;
    }

    private BasicFileAttributes attributes(Path path, Path affectedDirectory) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException | SecurityException failure) {
            recordFailure(affectedDirectory, failure);
            return null;
        }
    }

    private void recordFailure(Path path, Exception failure) {
        failedPaths.add(path.toAbsolutePath().normalize());
        LOGGER.warn("Failed to scan {}", path, failure);
    }

    private static String groupName(Path path, String fallback) {
        Path fileName = path.getFileName();
        return fileName == null ? fallback : fileName.toString();
    }

    /**
     * Returns the default Minecraft game root candidate for the current OS.
     * The returned path may not exist when Minecraft is not installed, so callers
     * must check that it exists and is a directory before scanning it.
     *
     * @return the default game root candidate, or the local MC directory when present
     */
    public static Path getDefaultGameRoot() {
        // 先检查当前项目根目录下是否有一个名为 "MC" 的自定义游戏目录
        Path localMC = Paths.get("MC");
        if (Files.exists(localMC) && Files.isDirectory(localMC)) {
            return localMC.toAbsolutePath();
        }

        String os = System.getProperty("os.name").toLowerCase();
        
        String userHome = System.getProperty("user.home");
        
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) {
                return Paths.get(appdata, ".minecraft");
            } else {
                return Paths.get(userHome, "AppData", "Roaming", ".minecraft");
            }
        } else if (os.contains("mac")) {
            return Paths.get(userHome, "Library", "Application Support", "minecraft");
        } else {
            return Paths.get(userHome, ".minecraft");
        }
    }
}
