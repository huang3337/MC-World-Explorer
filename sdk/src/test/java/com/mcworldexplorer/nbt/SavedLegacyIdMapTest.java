package com.mcworldexplorer.nbt;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SavedLegacyIdMapTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesForge17ItemDataFilteringItemsAndPreservingUppercaseModIds() throws IOException {
        Path world = Files.createDirectory(tempDir.resolve("forge-1710"));
        ListBinaryTag.Builder<CompoundBinaryTag> itemData = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        itemData.add(entry("\u0001minecraft:stone", 1));
        itemData.add(entry("\u0001  \tThaumcraft:blockMagicalLog ", 412));
        itemData.add(entry("\u0002minecraft:iron_shovel", 256));
        itemData.add(entry("\u0002Thaumcraft:itemWandCasting", 4097));

        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Data", CompoundBinaryTag.builder().putInt("version", 19133).build())
                .put("FML", CompoundBinaryTag.builder().put("ItemData", itemData.build()).build())
                .build();
        BinaryTagIO.writer().write(root, world.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        SavedLegacyIdMap idMap = SavedLegacyIdMap.readFromWorld(world);

        assertEquals(SavedLegacyIdMap.Source.FML_ITEM_DATA, idMap.source());
        assertTrue(idMap.hasFmlMappings());
        assertEquals(2, idMap.size());
        assertEquals(Optional.of("minecraft:stone"), idMap.resolveBlockName(1));
        assertEquals(Optional.of("Thaumcraft:blockMagicalLog"), idMap.resolveBlockName(412));
        assertEquals(Optional.empty(), idMap.resolveBlockName(256));
    }

    @Test
    void parsesForge112RegistriesFmlBlocks() throws IOException {
        Path world = Files.createDirectory(tempDir.resolve("forge-1122"));
        ListBinaryTag.Builder<CompoundBinaryTag> ids = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        ids.add(entry("minecraft:grass", 2));
        ids.add(entry("twilightforest:twilight_log", 512));

        CompoundBinaryTag fmlBlocks = CompoundBinaryTag.builder()
                .put("ids", ids.build())
                .build();
        CompoundBinaryTag registries = CompoundBinaryTag.builder()
                .put("fml:blocks", fmlBlocks)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Data", CompoundBinaryTag.builder().putInt("version", 19133).build())
                .put("FML", CompoundBinaryTag.builder().put("Registries", registries).build())
                .build();
        BinaryTagIO.writer().write(root, world.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        SavedLegacyIdMap idMap = SavedLegacyIdMap.readFromWorld(world);

        assertEquals(SavedLegacyIdMap.Source.FML_REGISTRIES, idMap.source());
        assertTrue(idMap.hasFmlMappings());
        assertEquals(2, idMap.size());
        assertEquals(Optional.of("minecraft:grass"), idMap.resolveBlockName(2));
        assertEquals(Optional.of("twilightforest:twilight_log"), idMap.resolveBlockName(512));
    }

    @Test
    void returnsVanillaOnlyWhenNoFmlTagOrMissingLevelDat() throws IOException {
        Path missingWorld = Files.createDirectory(tempDir.resolve("no-level-dat"));
        SavedLegacyIdMap missing = SavedLegacyIdMap.readFromWorld(missingWorld);
        assertEquals(SavedLegacyIdMap.Source.VANILLA_ONLY, missing.source());
        assertFalse(missing.hasFmlMappings());

        Path vanillaWorld = Files.createDirectory(tempDir.resolve("vanilla-1710"));
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Data", CompoundBinaryTag.builder()
                        .putString("LevelName", "Vanilla")
                        .putInt("version", 19133)
                        .build())
                .build();
        BinaryTagIO.writer().write(root, vanillaWorld.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        SavedLegacyIdMap vanilla = SavedLegacyIdMap.readFromWorld(vanillaWorld);
        assertEquals(SavedLegacyIdMap.Source.VANILLA_ONLY, vanilla.source());
        assertFalse(vanilla.hasFmlMappings());
        assertEquals(0, vanilla.size());
    }

    @Test
    void rejectsOutOfRangeConflictingAndMalformedFmlEntries() {
        CompoundBinaryTag outOfRange = CompoundBinaryTag.builder()
                .put("FML", CompoundBinaryTag.builder()
                        .put("ItemData", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                                .add(entry("\u0001mod:too_high", 4096))
                                .build())
                        .build())
                .build();
        assertThrows(IOException.class, () -> SavedLegacyIdMap.fromRootTag(outOfRange));

        CompoundBinaryTag negativeId = CompoundBinaryTag.builder()
                .put("FML", CompoundBinaryTag.builder()
                        .put("ItemData", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                                .add(entry("\u0001mod:negative", -1))
                                .build())
                        .build())
                .build();
        assertThrows(IOException.class, () -> SavedLegacyIdMap.fromRootTag(negativeId));

        CompoundBinaryTag conflict = CompoundBinaryTag.builder()
                .put("FML", CompoundBinaryTag.builder()
                        .put("ItemData", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                                .add(entry("\u0001mod:first", 300))
                                .add(entry("\u0001mod:second", 300))
                                .build())
                        .build())
                .build();
        assertThrows(IOException.class, () -> SavedLegacyIdMap.fromRootTag(conflict));

        CompoundBinaryTag malformedFml = CompoundBinaryTag.builder()
                .putString("FML", "corrupted")
                .build();
        assertThrows(IOException.class, () -> SavedLegacyIdMap.fromRootTag(malformedFml));

        CompoundBinaryTag malformedRegistry = CompoundBinaryTag.builder()
                .put("FML", CompoundBinaryTag.builder()
                        .put("Registries", CompoundBinaryTag.builder()
                                .put("fml:blocks", CompoundBinaryTag.builder()
                                        .putInt("ids", 42)
                                        .build())
                                .build())
                        .build())
                .build();
        assertThrows(IOException.class, () -> SavedLegacyIdMap.fromRootTag(malformedRegistry));
    }

    private static CompoundBinaryTag entry(String key, int value) {
        return CompoundBinaryTag.builder()
                .putString("K", key)
                .putInt("V", value)
                .build();
    }
}
