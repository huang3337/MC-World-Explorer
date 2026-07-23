package com.mcworldexplorer.nbt;

import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.GameType;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.NumberBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public class LevelDatReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(LevelDatReader.class);
    static final long MAX_LEVEL_DAT_BYTES = 16L * 1024 * 1024;
    private static final BinaryTagIO.Reader LEVEL_DAT_READER = BinaryTagIO.reader(MAX_LEVEL_DAT_BYTES);
    private static final BinaryTagIO.Compression[] SUPPORTED_COMPRESSIONS = {
            BinaryTagIO.Compression.GZIP,
            BinaryTagIO.Compression.NONE,
            BinaryTagIO.Compression.ZLIB
    };

    public static WorldInfo readLevelDat(Path worldFolder) throws IOException {
        Path levelDatPath = worldFolder.resolve("level.dat");
        if (!Files.exists(levelDatPath)) {
            throw new IOException("level.dat not found in " + worldFolder);
        }

        CompoundBinaryTag root = null;
        Exception lastFailure = null;
        for (BinaryTagIO.Compression compression : SUPPORTED_COMPRESSIONS) {
            try {
                root = LEVEL_DAT_READER.read(levelDatPath, compression);
                break;
            } catch (Exception e) {
                lastFailure = e;
                LOGGER.debug("Failed to read {} using {} compression", levelDatPath, compression, e);
            }
        }

        if (root == null) {
            LOGGER.warn("Failed to read level.dat for {} with supported compression formats", worldFolder, lastFailure);
            return createUnparsedWorld(worldFolder);
        }

        CompoundBinaryTag data = root.getCompound("Data");

        if (data == null || data.keySet().isEmpty()) {
            LOGGER.warn("Invalid level.dat format for {}: missing Data tag", worldFolder);
            return createUnparsedWorld(worldFolder);
        }

        WorldInfo info = new WorldInfo(worldFolder);

        try {
            BasicFileAttributes attributes = Files.readAttributes(worldFolder, BasicFileAttributes.class);
            info.setFolderCreationTime(attributes.creationTime().toMillis());
        } catch (IOException e) {
            LOGGER.debug("Failed to read folder creation time for {}", worldFolder, e);
        }

        // Basic Info
        if (data.keySet().contains("LevelName")) {
            info.setLevelName(data.getString("LevelName"));
        }
        if (data.keySet().contains("GameType")) {
            info.setGameType(GameType.fromId(data.getInt("GameType")));
        }
        if (data.keySet().contains("hardcore")) {
            info.setHardcore(data.getBoolean("hardcore"));
        }
        if (data.keySet().contains("LastPlayed")) {
            info.setLastPlayed(data.getLong("LastPlayed"));
        }
        if (data.keySet().contains("Time")) {
            info.setGameTime(data.getLong("Time"));
        }
        
        // Spawn
        if (data.keySet().contains("SpawnX")
                && data.keySet().contains("SpawnY")
                && data.keySet().contains("SpawnZ")) {
            info.setSpawnPosition(
                    data.getInt("SpawnX"),
                    data.getInt("SpawnY"),
                    data.getInt("SpawnZ"));
        }

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
                info.setPlayerPosition(
                        pos.getDouble(0),
                        pos.getDouble(1),
                        pos.getDouble(2),
                        readDimension(player.get("Dimension")));
            }
            if (player.keySet().contains("SpawnX")
                    && player.keySet().contains("SpawnY")
                    && player.keySet().contains("SpawnZ")) {
                info.setPlayerRespawnPosition(
                        player.getInt("SpawnX"),
                        player.getInt("SpawnY"),
                        player.getInt("SpawnZ"),
                        readDimension(player.get("SpawnDimension")));
            }
        }

        // Check for icon
        Path iconPath = worldFolder.resolve("icon.png");
        if (Files.exists(iconPath)) {
            info.setIconPath(iconPath);
        }

        info.setParsed(true);
        return info;
    }

    private static String readDimension(BinaryTag dimensionTag) {
        if (dimensionTag instanceof StringBinaryTag stringTag) {
            return stringTag.value();
        }
        if (dimensionTag instanceof NumberBinaryTag numberTag) {
            return Integer.toString(numberTag.intValue());
        }
        return dimensionTag == null ? null : "";
    }

    private static WorldInfo createUnparsedWorld(Path worldFolder) {
        WorldInfo fallback = new WorldInfo(worldFolder);
        Path iconPath = worldFolder.resolve("icon.png");
        if (Files.exists(iconPath)) {
            fallback.setIconPath(iconPath);
        }
        return fallback;
    }
}
