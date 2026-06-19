package com.mcworldexplorer.world;

import com.mcworldexplorer.nbt.LevelDatReader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorldScanner {

    /**
     * Scans the specified directory for Minecraft worlds.
     * A valid world must contain a level.dat file.
     *
     * @param savesDir The path to the saves directory (e.g., .minecraft/saves)
     * @return A list of WorldInfo objects for successfully parsed worlds.
     */
    public static List<WorldInfo> scanWorlds(Path savesDir) {
        List<WorldInfo> worlds = new ArrayList<>();

        if (savesDir == null || !Files.exists(savesDir) || !Files.isDirectory(savesDir)) {
            return worlds;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    // Check if level.dat exists in this folder
                    Path levelDat = entry.resolve("level.dat");
                    if (Files.exists(levelDat)) {
                        try {
                            WorldInfo info = LevelDatReader.readLevelDat(entry);
                            worlds.add(info);
                        } catch (Exception e) {
                            // Skip corrupted or unreadable worlds but log to console
                            System.err.println("Failed to read world at " + entry + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error while scanning directory " + savesDir + ": " + e.getMessage());
        }

        return worlds;
    }

    /**
     * Scans a given game root directory for both default saves and version-isolated saves.
     */
    public static Map<String, List<WorldInfo>> scanGameRoot(Path rootDir) {
        Map<String, List<WorldInfo>> resultMap = new LinkedHashMap<>();
        
        // 1. Check default saves in the root
        Path directSaves = rootDir.resolve("saves");
        if (Files.exists(directSaves) && Files.isDirectory(directSaves)) {
            List<WorldInfo> defaultWorlds = scanWorlds(directSaves);
            if (!defaultWorlds.isEmpty()) {
                resultMap.put("默认存档 (Default)", defaultWorlds);
            }
        }

        // 2. Check isolated versions
        Path versionsDir = rootDir.resolve("versions");
        if (Files.exists(versionsDir) && Files.isDirectory(versionsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionsDir)) {
                for (Path versionFolder : stream) {
                    if (Files.isDirectory(versionFolder)) {
                        Path versionSaves = versionFolder.resolve("saves");
                        if (Files.exists(versionSaves) && Files.isDirectory(versionSaves)) {
                            List<WorldInfo> versionWorlds = scanWorlds(versionSaves);
                            if (!versionWorlds.isEmpty()) {
                                resultMap.put(versionFolder.getFileName().toString(), versionWorlds);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to scan versions dir: " + e.getMessage());
            }
        }
        
        return resultMap;
    }

    /**
     * Attempts to find the default Minecraft game root directory based on OS.
     * @return The path to the root directory, or null if not found.
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

