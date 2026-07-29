package com.mcworldexplorer.nbt;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.world.PlayerLocation;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.NumberBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class PlayerDataReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerDataReader.class);
    private static final long MAX_PLAYER_DATA_BYTES = 16L * 1024 * 1024;
    private static final long MAX_NAME_CACHE_BYTES = 4L * 1024 * 1024;
    private static final BinaryTagIO.Reader PLAYER_READER =
            BinaryTagIO.reader(MAX_PLAYER_DATA_BYTES);
    private static final Pattern PLAYER_FILE = Pattern.compile(
            "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.dat$");
    private static final Pattern USERNAME_CACHE_ENTRY = Pattern.compile(
            "\"([0-9a-fA-F-]{36})\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern JSON_STRING_FIELD = Pattern.compile(
            "\"(name|uuid)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    public List<PlayerLocation> read(Path worldFolder) throws IOException {
        if (worldFolder == null) {
            throw new IllegalArgumentException("worldFolder must not be null");
        }
        Path normalizedWorld = worldFolder.toAbsolutePath().normalize();
        Path playerDirectory = normalizedWorld.resolve("playerdata");
        if (!Files.isDirectory(playerDirectory)) {
            return List.of();
        }

        Map<UUID, String> names = readPlayerNames(normalizedWorld);
        List<Path> playerFiles;
        try (Stream<Path> files = Files.list(playerDirectory)) {
            playerFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> PLAYER_FILE.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }

        List<PlayerLocation> players = new ArrayList<>();
        for (Path playerFile : playerFiles) {
            try {
                readPlayer(playerFile, names).ifPresent(players::add);
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Failed to read player data {}", playerFile, failure);
            }
        }
        players.sort(Comparator
                .comparing(PlayerLocation::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(player -> player.uuid().toString()));
        return List.copyOf(players);
    }

    private static Optional<PlayerLocation> readPlayer(
            Path playerFile,
            Map<UUID, String> names) throws IOException {
        Matcher filename = PLAYER_FILE.matcher(playerFile.getFileName().toString());
        if (!filename.matches()) {
            return Optional.empty();
        }
        UUID uuid = UUID.fromString(filename.group(1).toLowerCase(Locale.ROOT));
        CompoundBinaryTag root = PLAYER_READER.read(
                playerFile,
                BinaryTagIO.Compression.GZIP);
        ListBinaryTag position = root.getList("Pos", BinaryTagTypes.DOUBLE);
        if (position == null || position.size() < 3) {
            return Optional.empty();
        }
        double x = position.getDouble(0);
        double y = position.getDouble(1);
        double z = position.getDouble(2);
        String dimensionId = readDimension(root.get("Dimension"));
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || dimensionId == null || dimensionId.isBlank()) {
            return Optional.empty();
        }
        String displayName = names.getOrDefault(uuid, uuid.toString().substring(0, 8));
        return Optional.of(new PlayerLocation(
                uuid,
                displayName,
                WorldDimension.normalizeId(dimensionId),
                x,
                y,
                z,
                Files.getLastModifiedTime(playerFile).toMillis()));
    }

    private static Map<UUID, String> readPlayerNames(Path worldFolder) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Path directory : candidateDirectories(worldFolder)) {
            readUsernameCache(directory.resolve("usernamecache.json"), names);
            readUserCache(directory.resolve("usercache.json"), names);
        }
        return Map.copyOf(names);
    }

    private static Set<Path> candidateDirectories(Path worldFolder) {
        Set<Path> candidates = new LinkedHashSet<>();
        Path current = worldFolder;
        for (int depth = 0; depth < 3 && current != null; depth++) {
            candidates.add(current);
            current = current.getParent();
        }
        return candidates;
    }

    private static void readUsernameCache(Path cacheFile, Map<UUID, String> names) {
        readCacheText(cacheFile).ifPresent(json -> {
            Matcher entries = USERNAME_CACHE_ENTRY.matcher(json);
            while (entries.find()) {
                addName(names, entries.group(1), entries.group(2));
            }
        });
    }

    private static void readUserCache(Path cacheFile, Map<UUID, String> names) {
        readCacheText(cacheFile).ifPresent(json -> {
            Matcher objects = JSON_OBJECT.matcher(json);
            while (objects.find()) {
                String uuid = null;
                String name = null;
                Matcher fields = JSON_STRING_FIELD.matcher(objects.group(1));
                while (fields.find()) {
                    if ("uuid".equals(fields.group(1))) {
                        uuid = fields.group(2);
                    } else if ("name".equals(fields.group(1))) {
                        name = fields.group(2);
                    }
                }
                if (uuid != null && name != null) {
                    addName(names, uuid, name);
                }
            }
        });
    }

    private static Optional<String> readCacheText(Path cacheFile) {
        try {
            if (!Files.isRegularFile(cacheFile)
                    || Files.size(cacheFile) > MAX_NAME_CACHE_BYTES) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(cacheFile, StandardCharsets.UTF_8));
        } catch (IOException | SecurityException failure) {
            LOGGER.warn("Failed to read player name cache {}", cacheFile, failure);
            return Optional.empty();
        }
    }

    private static void addName(
            Map<UUID, String> names,
            String encodedUuid,
            String encodedName) {
        try {
            UUID uuid = UUID.fromString(encodedUuid);
            String name = unescapeJson(encodedName).orElse("");
            if (!name.isBlank()) {
                names.putIfAbsent(uuid, name);
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed cache entries without discarding other names.
        }
    }

    private static Optional<String> unescapeJson(String encoded) {
        StringBuilder decoded = new StringBuilder(encoded.length());
        for (int index = 0; index < encoded.length(); index++) {
            char current = encoded.charAt(index);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (++index >= encoded.length()) {
                return Optional.empty();
            }
            char escape = encoded.charAt(index);
            switch (escape) {
                case '"', '\\', '/' -> decoded.append(escape);
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'u' -> {
                    if (index + 4 >= encoded.length()) {
                        return Optional.empty();
                    }
                    try {
                        decoded.append((char) Integer.parseInt(
                                encoded.substring(index + 1, index + 5),
                                16));
                    } catch (NumberFormatException failure) {
                        return Optional.empty();
                    }
                    index += 4;
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(decoded.toString());
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
}
