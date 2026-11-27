package com.mcworldexplorer.nbt;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded read-only parser for Forge/FML numeric block ID mappings stored in {@code level.dat}.
 * Supports Forge 1.7.x ({@code FML.ItemData}) and Forge 1.8–1.12.2 ({@code FML.Registries}).
 */
public final class SavedLegacyIdMap {
    public static final int MAX_BLOCK_ID = 4095;
    public static final int MAX_ENTRIES = 32_768;

    private static final char FML_17_BLOCK_PREFIX = '\u0001';
    private static final char FML_17_ITEM_PREFIX = '\u0002';

    private static final SavedLegacyIdMap VANILLA_ONLY = new SavedLegacyIdMap(Source.VANILLA_ONLY, Map.of());

    public enum Source {
        VANILLA_ONLY,
        FML_ITEM_DATA,
        FML_REGISTRIES
    }

    private final Source source;
    private final Map<Integer, String> blockIds;

    private SavedLegacyIdMap(Source source, Map<Integer, String> blockIds) {
        this.source = Objects.requireNonNull(source, "source");
        this.blockIds = Collections.unmodifiableMap(new HashMap<>(blockIds));
    }

    public static SavedLegacyIdMap vanillaOnly() {
        return VANILLA_ONLY;
    }

    public static SavedLegacyIdMap of(Source source, Map<Integer, String> blockIds) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(blockIds, "blockIds");
        if (blockIds.isEmpty() && source == Source.VANILLA_ONLY) {
            return VANILLA_ONLY;
        }
        Map<Integer, String> validated = new HashMap<>(blockIds.size());
        for (Map.Entry<Integer, String> entry : blockIds.entrySet()) {
            Integer id = entry.getKey();
            String name = entry.getValue();
            if (id == null || id < 0 || id > MAX_BLOCK_ID) {
                throw new IllegalArgumentException("Legacy block ID out of range [0.." + MAX_BLOCK_ID + "]: " + id);
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Legacy block name must not be blank for ID " + id);
            }
            validated.put(id, name);
        }
        return new SavedLegacyIdMap(source, validated);
    }

    /**
     * Reads Forge/FML block ID mappings from {@code worldFolder/level.dat}.
     * Returns {@link #vanillaOnly()} when {@code level.dat} is absent or contains no FML registry tags.
     *
     * @throws IOException if {@code level.dat} cannot be read or its FML registry data is malformed/conflicting
     */
    public static SavedLegacyIdMap readFromWorld(Path worldFolder) throws IOException {
        Objects.requireNonNull(worldFolder, "worldFolder");
        Path levelDatPath = worldFolder.resolve("level.dat");
        if (!Files.isRegularFile(levelDatPath)) {
            return VANILLA_ONLY;
        }
        CompoundBinaryTag root = LevelDatReader.readRootTag(levelDatPath);
        return fromRootTag(root);
    }

    /**
     * Parses Forge/FML block ID mappings from a {@code level.dat} root compound tag.
     *
     * @throws IOException if FML registry tags are malformed, out of bounds, or contain conflicting IDs
     */
    public static SavedLegacyIdMap fromRootTag(CompoundBinaryTag root) throws IOException {
        Objects.requireNonNull(root, "root");
        BinaryTag fmlTag = root.get("FML");
        if (fmlTag == null) {
            return VANILLA_ONLY;
        }
        if (!(fmlTag instanceof CompoundBinaryTag fml)) {
            throw new IOException("Invalid FML tag in level.dat: expected CompoundTag, got " + fmlTag.type());
        }

        BinaryTag registriesTag = fml.get("Registries");
        if (registriesTag != null) {
            if (!(registriesTag instanceof CompoundBinaryTag registries)) {
                throw new IOException("Invalid FML.Registries tag: expected CompoundTag, got " + registriesTag.type());
            }
            Optional<SavedLegacyIdMap> parsedRegistries = parseRegistries(registries);
            if (parsedRegistries.isPresent()) {
                return parsedRegistries.get();
            }
        }

        BinaryTag itemDataTag = fml.get("ItemData");
        if (itemDataTag != null) {
            return parseItemData(itemDataTag);
        }

        return VANILLA_ONLY;
    }

    private static Optional<SavedLegacyIdMap> parseRegistries(CompoundBinaryTag registries) throws IOException {
        String registryKey = null;
        if (registries.get("fml:blocks") != null) {
            registryKey = "fml:blocks";
        } else if (registries.get("minecraft:blocks") != null) {
            registryKey = "minecraft:blocks";
        }
        if (registryKey == null) {
            return Optional.empty();
        }

        BinaryTag blocksTag = registries.get(registryKey);
        if (!(blocksTag instanceof CompoundBinaryTag blocksRegistry)) {
            throw new IOException("Invalid FML.Registries." + registryKey + " tag: expected CompoundTag");
        }
        BinaryTag idsTag = blocksRegistry.get("ids");
        if (idsTag == null) {
            throw new IOException("Missing ids list in FML.Registries." + registryKey);
        }
        if (!(idsTag instanceof ListBinaryTag idsList)) {
            throw new IOException("Invalid FML.Registries." + registryKey + ".ids: expected ListTag");
        }
        if (idsList.size() > MAX_ENTRIES) {
            throw new IOException("FML.Registries." + registryKey + ".ids exceeds maximum entry limit ("
                    + MAX_ENTRIES + "): " + idsList.size());
        }
        if (idsList.size() > 0 && idsList.elementType() != BinaryTagTypes.COMPOUND) {
            throw new IOException("Invalid FML.Registries." + registryKey
                    + ".ids element type: expected CompoundTag, got " + idsList.elementType());
        }

        Map<Integer, String> mapping = new HashMap<>();
        for (int i = 0; i < idsList.size(); i++) {
            CompoundBinaryTag entry = idsList.getCompound(i);
            String rawKey = requireStringField(entry, "K", "FML.Registries." + registryKey + ".ids[" + i + "]");
            int id = requireIntField(entry, "V", "FML.Registries." + registryKey + ".ids[" + i + "]");
            String normalized = normalizeBlockName(rawKey);
            if (normalized.isEmpty()) {
                throw new IOException("Empty block name in FML.Registries." + registryKey + ".ids[" + i + "]");
            }
            putValidated(mapping, id, normalized, "FML.Registries." + registryKey + ".ids[" + i + "]");
        }
        return Optional.of(new SavedLegacyIdMap(Source.FML_REGISTRIES, mapping));
    }

    private static SavedLegacyIdMap parseItemData(BinaryTag itemDataTag) throws IOException {
        if (!(itemDataTag instanceof ListBinaryTag itemDataList)) {
            throw new IOException("Invalid FML.ItemData tag: expected ListTag, got " + itemDataTag.type());
        }
        if (itemDataList.size() > MAX_ENTRIES) {
            throw new IOException("FML.ItemData exceeds maximum entry limit ("
                    + MAX_ENTRIES + "): " + itemDataList.size());
        }
        if (itemDataList.size() > 0 && itemDataList.elementType() != BinaryTagTypes.COMPOUND) {
            throw new IOException("Invalid FML.ItemData element type: expected CompoundTag, got "
                    + itemDataList.elementType());
        }

        Map<Integer, String> mapping = new HashMap<>();
        for (int i = 0; i < itemDataList.size(); i++) {
            CompoundBinaryTag entry = itemDataList.getCompound(i);
            String rawKey = requireStringField(entry, "K", "FML.ItemData[" + i + "]");
            int id = requireIntField(entry, "V", "FML.ItemData[" + i + "]");
            if (rawKey.isEmpty()) {
                throw new IOException("Empty key in FML.ItemData[" + i + "]");
            }
            char prefix = rawKey.charAt(0);
            if (prefix == FML_17_ITEM_PREFIX) {
                continue;
            }
            if (prefix != FML_17_BLOCK_PREFIX) {
                continue;
            }
            String normalized = normalizeBlockName(rawKey.substring(1));
            if (normalized.isEmpty()) {
                throw new IOException("Empty block name after 0x01 prefix in FML.ItemData[" + i + "]");
            }
            putValidated(mapping, id, normalized, "FML.ItemData[" + i + "]");
        }
        return new SavedLegacyIdMap(Source.FML_ITEM_DATA, mapping);
    }

    private static String requireStringField(CompoundBinaryTag entry, String key, String location) throws IOException {
        BinaryTag tag = entry.get(key);
        if (!(tag instanceof StringBinaryTag stringTag)) {
            throw new IOException("Invalid or missing string field '" + key + "' at " + location);
        }
        return stringTag.value();
    }

    private static int requireIntField(CompoundBinaryTag entry, String key, String location) throws IOException {
        BinaryTag tag = entry.get(key);
        if (!(tag instanceof IntBinaryTag intTag)) {
            throw new IOException("Invalid or missing int field '" + key + "' at " + location);
        }
        return intTag.value();
    }

    private static void putValidated(Map<Integer, String> mapping, int id, String name, String location)
            throws IOException {
        if (id < 0 || id > MAX_BLOCK_ID) {
            throw new IOException("Block ID out of range [0.." + MAX_BLOCK_ID + "] at " + location
                    + ": id=" + id + ", name=" + name);
        }
        String existing = mapping.putIfAbsent(id, name);
        if (existing != null && !existing.equals(name)) {
            throw new IOException("Conflicting legacy block ID " + id + " at " + location
                    + ": '" + existing + "' vs '" + name + "'");
        }
    }

    private static String normalizeBlockName(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && (raw.charAt(start) <= ' ' || Character.isISOControl(raw.charAt(start)))) {
            start++;
        }
        while (end > start && (raw.charAt(end - 1) <= ' ' || Character.isISOControl(raw.charAt(end - 1)))) {
            end--;
        }
        return raw.substring(start, end);
    }

    public Source source() {
        return source;
    }

    public boolean hasFmlMappings() {
        return source != Source.VANILLA_ONLY;
    }

    public int size() {
        return blockIds.size();
    }

    public Optional<String> resolveBlockName(int blockId) {
        if (blockId < 0 || blockId > MAX_BLOCK_ID) {
            return Optional.empty();
        }
        return Optional.ofNullable(blockIds.get(blockId));
    }

    public Map<Integer, String> blockIds() {
        return blockIds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SavedLegacyIdMap that)) return false;
        return source == that.source && blockIds.equals(that.blockIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, blockIds);
    }

    @Override
    public String toString() {
        return "SavedLegacyIdMap{source=" + source + ", entries=" + blockIds.size() + "}";
    }
}
