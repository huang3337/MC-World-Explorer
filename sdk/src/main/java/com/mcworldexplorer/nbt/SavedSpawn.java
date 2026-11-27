package com.mcworldexplorer.nbt;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.NumberBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import java.util.Optional;

/** Normalizes the flat and structured position encodings before viewport selection. */
record SavedSpawn(int x, int y, int z, String dimension) {
    static Optional<SavedSpawn> read(CompoundBinaryTag owner, String structuredKey) {
        if (owner.get(structuredKey) != null) {
            var position = owner.getCompound(structuredKey);
            if (!(position.get("pos") instanceof IntArrayBinaryTag coords) || coords.size() != 3) {
                return Optional.empty();
            }
            if (!(position.get("dimension") instanceof StringBinaryTag id) || id.value().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new SavedSpawn(coords.get(0), coords.get(1), coords.get(2), id.value()));
        }
        if (!(owner.get("SpawnX") instanceof IntBinaryTag x)
                || !(owner.get("SpawnY") instanceof IntBinaryTag y)
                || !(owner.get("SpawnZ") instanceof IntBinaryTag z)) {
            return Optional.empty();
        }
        String dimension = readDimension(owner.get("SpawnDimension"));
        if (dimension != null && dimension.isBlank()) return Optional.empty();
        return Optional.of(new SavedSpawn(x.value(), y.value(), z.value(), dimension));
    }

    static String readDimension(BinaryTag tag) {
        if (tag instanceof StringBinaryTag id) return id.value();
        if (tag instanceof NumberBinaryTag id) return Integer.toString(id.intValue());
        return tag == null ? null : "";
    }
}
