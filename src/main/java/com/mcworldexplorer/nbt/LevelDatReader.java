package com.mcworldexplorer.nbt;

import com.mcworldexplorer.world.WorldInfo;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LevelDatReader {

    public static WorldInfo readLevelDat(Path worldFolder) throws IOException {
        Path levelDatPath = worldFolder.resolve("level.dat");
        if (!Files.exists(levelDatPath)) {
            throw new IOException("level.dat not found in " + worldFolder);
        }

        CompoundBinaryTag root = null;
        try {
            root = BinaryTagIO.reader().read(levelDatPath, BinaryTagIO.Compression.GZIP);
        } catch (Exception e) {
            try {
                root = BinaryTagIO.reader().read(levelDatPath, BinaryTagIO.Compression.NONE);
            } catch (Exception e2) {
                try {
                    root = BinaryTagIO.reader().read(levelDatPath, BinaryTagIO.Compression.ZLIB);
                } catch (Exception e3) {
                    System.err.println("Failed to read level.dat for " + worldFolder + " with any compression.");
                    // Graceful Degradation
                    WorldInfo fallback = new WorldInfo(worldFolder);
                    fallback.setLevelName(worldFolder.getFileName().toString());
                    fallback.setVersionName("解析失败");
                    
                    Path iconPath = worldFolder.resolve("icon.png");
                    if (Files.exists(iconPath)) {
                        fallback.setIconPath(iconPath);
                    }
                    return fallback;
                }
            }
        }

        CompoundBinaryTag data = root.getCompound("Data");
        
        if (data == null || data.keySet().isEmpty()) {
            throw new IOException("Invalid level.dat format (missing 'Data' tag)");
        }

        WorldInfo info = new WorldInfo(worldFolder);
        
        // Basic Info
        info.setLevelName(data.getString("LevelName"));
        info.setGameType(data.getInt("GameType"));
        info.setHardcore(data.getBoolean("hardcore"));
        info.setLastPlayed(data.getLong("LastPlayed"));
        info.setGameTime(data.getLong("Time"));
        
        // Spawn
        info.setSpawnX(data.getInt("SpawnX"));
        info.setSpawnY(data.getInt("SpawnY"));
        info.setSpawnZ(data.getInt("SpawnZ"));

        // Version (1.9+)
        CompoundBinaryTag versionTag = data.getCompound("Version");
        if (versionTag != null && !versionTag.keySet().isEmpty()) {
            info.setVersionName(versionTag.getString("Name"));
        } else {
            info.setVersionName("Unknown/Old");
        }

        // Seed
        if (data.keySet().contains("RandomSeed")) {
            info.setRandomSeed(data.getLong("RandomSeed"));
        } else {
            CompoundBinaryTag wgs = data.getCompound("WorldGenSettings");
            if (wgs != null && wgs.keySet().contains("seed")) {
                info.setRandomSeed(wgs.getLong("seed"));
            }
        }

        // Player Pos
        CompoundBinaryTag player = data.getCompound("Player");
        if (player != null && !player.keySet().isEmpty()) {
            ListBinaryTag pos = player.getList("Pos", BinaryTagTypes.DOUBLE);
            if (pos != null && pos.size() >= 3) {
                info.setPlayerX(pos.getDouble(0));
                info.setPlayerY(pos.getDouble(1));
                info.setPlayerZ(pos.getDouble(2));
            }
        }

        // Check for icon
        Path iconPath = worldFolder.resolve("icon.png");
        if (Files.exists(iconPath)) {
            info.setIconPath(iconPath);
        }

        return info;
    }
}
