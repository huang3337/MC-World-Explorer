package com.mcworldexplorer.nbt;

import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.GameType;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public class LevelDatReader {
    public static final int ANVIL_DATA_VERSION_FLAG = 19133;
    public static final String LEGACY_PRE_1_9_VERSION = "Anvil (<1.9)";

    private static final Logger LOGGER = LoggerFactory.getLogger(LevelDatReader.class);
    static final long MAX_LEVEL_DAT_BYTES = 16L * 1024 * 1024;
    private static final BinaryTagIO.Reader LEVEL_DAT_READER = BinaryTagIO.reader(MAX_LEVEL_DAT_BYTES);
    private static final BinaryTagIO.Compression[] SUPPORTED_COMPRESSIONS = {
            BinaryTagIO.Compression.GZIP,
            BinaryTagIO.Compression.NONE,
            BinaryTagIO.Compression.ZLIB
    };

    static CompoundBinaryTag readRootTag(Path levelDatPath) throws IOException {
        Exception lastFailure = null;
        for (BinaryTagIO.Compression compression : SUPPORTED_COMPRESSIONS) {
            try {
                return LEVEL_DAT_READER.read(levelDatPath, compression);
            } catch (Exception e) {
                lastFailure = e;
                LOGGER.debug("Failed to read {} using {} compression", levelDatPath, compression, e);
            }
        }
        throw new IOException("Failed to read level.dat with supported compression formats: " + levelDatPath, lastFailure);
    }

    public static WorldInfo readLevelDat(Path worldFolder) throws IOException {
        Path levelDatPath = worldFolder.resolve("level.dat");
        if (!Files.exists(levelDatPath)) {
            throw new IOException("level.dat not found in " + worldFolder);
        }

        CompoundBinaryTag root;
        try {
            root = readRootTag(levelDatPath);
        } catch (IOException e) {
            LOGGER.warn("Failed to read level.dat for {} with supported compression formats", worldFolder, e.getCause());
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
        SavedSpawn.read(data, "spawn").ifPresent(spawn ->
                info.setSpawnPosition(spawn.x(), spawn.y(), spawn.z(), spawn.dimension()));

        // Version (1.9+ has Data.Version; pre-1.9 Anvil saves have Data.version == 19133)
        CompoundBinaryTag versionTag = data.getCompound("Version");
        String versionName = versionTag != null && !versionTag.keySet().isEmpty()
                ? versionTag.getString("Name")
                : "";
        if (!versionName.isBlank()) {
            info.setVersionName(versionName);
        } else if (data.getInt("version") == ANVIL_DATA_VERSION_FLAG) {
            info.setVersionName(LEGACY_PRE_1_9_VERSION);
        } else {
            info.setVersionName("Unknown");
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
                        SavedSpawn.readDimension(player.get("Dimension")));
            }
            SavedSpawn.read(player, "respawn").ifPresent(spawn ->
                    info.setPlayerRespawnPosition(spawn.x(), spawn.y(), spawn.z(), spawn.dimension()));
        }

        // Check for icon
        Path iconPath = worldFolder.resolve("icon.png");
        if (Files.exists(iconPath)) {
            info.setIconPath(iconPath);
        }

        info.setParsed(true);
        return info;
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
