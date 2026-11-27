package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.voxel.data.BlockState;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import java.util.HashMap;
import java.util.Map;
import static com.mcworldexplorer.voxel.io.ChunkDecodeException.Reason.INVALID_PALETTE;
import static com.mcworldexplorer.voxel.io.ChunkDecodeException.Reason.INVALID_PALETTE_PROPERTY;

/** Reads the encoding by shape and uses the chunk's DataVersion only for registry facts. */
record BlockPaletteDecoder(int dataVersion, boolean scene) {
    BlockState read(BinaryTag entry, int sectionY, int index) throws ChunkDecodeException {
        if (entry instanceof StringBinaryTag text) return state(text.value(), Map.of(), true, sectionY, index);
        if (!(entry instanceof CompoundBinaryTag tag)) {
            throw new ChunkDecodeException(INVALID_PALETTE, sectionY, "invalid palette entry " + index);
        }
        if (tag.keySet().size() == 1 && tag.get("") instanceof StringBinaryTag wrapped) {
            return state(wrapped.value(), Map.of(), true, sectionY, index);
        }
        BinaryTag modern = tag.get("id"), legacy = tag.get("Name");
        if (modern != null && legacy != null && !modern.equals(legacy)) {
            throw new ChunkDecodeException(INVALID_PALETTE, sectionY, "conflicting block name fields");
        }
        if (!((modern == null ? legacy : modern) instanceof StringBinaryTag id)) {
            throw new ChunkDecodeException(INVALID_PALETTE, sectionY, "missing block ID at palette entry " + index);
        }
        BinaryTag properties = tag.get("properties"), oldProperties = tag.get("Properties");
        if (properties != null && oldProperties != null && !properties.equals(oldProperties)) {
            throw new ChunkDecodeException(INVALID_PALETTE_PROPERTY, sectionY, "conflicting property fields");
        }
        var written = readProperties(properties == null ? oldProperties : properties, sectionY);
        return state(id.value(), written, modern != null || dataVersion >= 0, sectionY, index);
    }

    private BlockState state(String id, Map<String, String> written, boolean usesDefaults,
                             int sectionY, int index) throws ChunkDecodeException {
        if (id.isBlank()) throw new ChunkDecodeException(INVALID_PALETTE, sectionY, "empty ID at palette entry " + index);
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        if (!scene) return new BlockState(id, Map.of());
        return usesDefaults ? VanillaBlockDefaults.resolve(id, written, dataVersion) : new BlockState(id, written);
    }

    private Map<String, String> readProperties(BinaryTag tag, int sectionY) throws ChunkDecodeException {
        if (tag == null) return Map.of();
        if (!(tag instanceof CompoundBinaryTag properties)) {
            throw new ChunkDecodeException(INVALID_PALETTE_PROPERTY, sectionY, "block properties must be a compound");
        }
        Map<String, String> written = scene ? new HashMap<>() : Map.of();
        for (String key : properties.keySet()) {
            if (key.isBlank() || !(properties.get(key) instanceof StringBinaryTag text) || text.value().isBlank()) {
                throw new ChunkDecodeException(INVALID_PALETTE_PROPERTY, sectionY, "invalid block property " + key);
            }
            if (scene) written.put(key, text.value());
        }
        return written;
    }
}
