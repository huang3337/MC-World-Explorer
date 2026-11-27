package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.nbt.SavedLegacyIdMap;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkNbtDecoderTest {
    @Test void preservesSavedBiomeCellsIndependentlyOfAirGeometryAndNegativeSectionCoordinates()throws Exception {
        var palette=ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING,List.of(net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag("author:forest"),net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag("author:desert")));
        var biomes=CompoundBinaryTag.builder().put("palette",palette).putLongArray("data",new long[]{1L<<63}).build();
        var airOnly=CompoundBinaryTag.builder().putByte("Y",(byte)-1).put("biomes",biomes).build();
        var root=CompoundBinaryTag.builder().put("sections",sections(airOnly)).build();
        var chunk=ChunkNbtDecoder.read(stream(root),-3,2).chunk();
        assertEquals(BlockState.AIR,chunk.blockState(15,-1,15));assertEquals(java.util.Optional.of("author:desert"),chunk.biomeAtQuart(3,-1,3));
        assertEquals(java.util.Optional.of("author:forest"),chunk.biomeAtQuart(0,-4,0));assertTrue(chunk.biomeAtQuart(0,0,0).isEmpty());
        assertTrue(chunk.minY().isEmpty(),"biome-only sections must not expand geometry bounds");
    }
    @Test void malformedBiomesDoNotDiscardReadableBlocksOrBecomeDefaults()throws Exception {
        var valid=section(0,palette(named("minecraft:stone")),new long[0]);
        var invalid=CompoundBinaryTag.builder().put(valid).put("biomes",CompoundBinaryTag.empty()).build();
        var chunk=ChunkNbtDecoder.read(stream(modernChunk(invalid))).chunk();
        assertEquals("minecraft:stone",chunk.blockState(0,0,0).name());assertEquals(com.mcworldexplorer.voxel.data.BiomeSection.Status.INVALID,chunk.biomeSection(0).orElseThrow().status());
        assertTrue(chunk.biomeAtQuart(0,0,0).isEmpty());
    }
    @Test void readsStringAndWrappedModernPaletteEntriesWithoutVersionWhitelist()throws Exception {
        var strings=ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING,List.of(net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag("minecraft:stone")));
        var root=CompoundBinaryTag.builder().put("sections",sections(section(0,strings,new long[0]))).build();
        assertEquals("minecraft:stone",ChunkNbtDecoder.read(stream(root),0,0).chunk().blockState(0,0,0).name());
        var wrapped=CompoundBinaryTag.builder().putString("","minecraft:stone").build();
        var object=CompoundBinaryTag.builder().putString("id","minecraft:oak_log").put("properties",CompoundBinaryTag.builder().putString("axis","z").build()).build();
        long[] data=new long[256];data[0]=0x10;
        root=CompoundBinaryTag.builder().putInt("DataVersion",5023).put("sections",sections(section(0,palette(wrapped,object),data))).build();
        var chunk=ChunkNbtDecoder.read(stream(root),0,0).chunk();assertEquals(new BlockState("minecraft:oak_log",Map.of("axis","z")),chunk.blockState(1,0,0));
        assertEquals(4095,chunk.sections().getFirst().usedStateCounts().get(new BlockState("minecraft:stone",Map.of())));
    }
    @Test void conflictingModernAndLegacyStateFieldsRemainErrors()throws Exception {
        var conflict=CompoundBinaryTag.builder().putString("Name","minecraft:stone").putString("id","minecraft:dirt").build();
        var root=CompoundBinaryTag.builder().put("sections",sections(section(0,palette(conflict),new long[0]))).build();
        assertEquals(ChunkDecodeException.Reason.INVALID_PALETTE,assertThrows(ChunkDecodeException.class,()->ChunkNbtDecoder.read(stream(root),0,0)).reason());
    }

    @Test void restoresOnlyOmittedVanillaPropertiesAtSceneDecodeTime() throws Exception {
        var upper = CompoundBinaryTag.builder().putString("id", "tall_seagrass")
                .put("properties", CompoundBinaryTag.builder().putString("half", "upper").build()).build();
        var stairs = CompoundBinaryTag.builder().putString("id", "minecraft:oak_stairs")
                .put("properties", CompoundBinaryTag.builder().putString("facing", "west").build()).build();
        var palette = palette(CompoundBinaryTag.builder().putString("", "minecraft:deepslate").build(),
                CompoundBinaryTag.builder().putString("id", "tall_seagrass").build(), upper, stairs,
                CompoundBinaryTag.builder().putString("id","minecraft:grass_block").build(),
                CompoundBinaryTag.builder().putString("id","author:grass_block").build());
        long[] indices = new long[256];
        indices[0] = 0x543210;
        for (int version : List.of(3465, 3955, 5023)) {
            var root = CompoundBinaryTag.builder().putInt("DataVersion", version)
                    .put("sections", sections(section(0, palette, indices))).build();
            var scene = ChunkNbtDecoder.read(stream(root)).chunk();
            var surface = ChunkNbtDecoder.readSurface(stream(root)).chunk();
            var deepslate = scene.blockState(0,0,0);
            var explicit = new BlockState("minecraft:deepslate", Map.of("axis","y"));
            assertEquals(explicit, deepslate);
            assertEquals(explicit.hashCode(), deepslate.hashCode());
            assertEquals(version, deepslate.savedForm().dataVersion());
            assertTrue(deepslate.savedProperties().isEmpty());
            assertEquals("lower", scene.blockState(1,0,0).properties().get("half"));
            assertEquals("upper", scene.blockState(2,0,0).properties().get("half"));
            assertEquals(Map.of("facing","west","half","bottom","shape","straight","waterlogged","false"),
                    scene.blockState(3,0,0).properties());
            assertEquals(Map.of("facing","west"), scene.blockState(3,0,0).savedProperties());
            var olderShape = CompoundBinaryTag.builder().putString("Name", "minecraft:oak_stairs")
                    .put("Properties", stairs.getCompound("properties")).build();
            assertEquals(scene.blockState(3,0,0), new BlockPaletteDecoder(version,true).read(olderShape,0,0));
            assertEquals(Map.of("snowy","false"), scene.blockState(4,0,0).properties());
            assertTrue(scene.blockState(5,0,0).properties().isEmpty());
            assertEquals(new BlockState("minecraft:tall_seagrass", Map.of()), surface.blockState(1,0,0));
        }
        var missing = new BlockPaletteDecoder(999999, true).read(stairs, 0, 0);
        assertEquals(Map.of("facing","west"), missing.properties());
        assertEquals(false, missing.savedForm().defaultsKnown());
    }
    @Test void savedRedstoneUsesTheRegisteredPowerOnlyWhenThePaletteOmitsIt()throws Exception {
        var decoder=new BlockPaletteDecoder(3465,true);
        var wire=CompoundBinaryTag.builder().putString("Name","minecraft:redstone_wire");
        assertEquals("0",decoder.read(wire.build(),0,0).properties().get("power"));
        wire.put("Properties",CompoundBinaryTag.builder().putString("power","11").build());
        assertEquals("11",decoder.read(wire.build(),0,0).properties().get("power"));
    }
    @Test void readsModernAndLegacyVisualEntitiesAndRejectsSilentTruncation() throws Exception {
        var entity=CompoundBinaryTag.builder().putInt("x",1).putInt("y",0).putInt("z",2).putString("id","minecraft:sign").putString("Text1","Hello").put("Items",ListBinaryTag.empty()).build();
        for(boolean modern:List.of(true,false)) {
            var container=CompoundBinaryTag.builder().putInt("xPos",0).putInt("zPos",0).put(modern?"sections":"Sections",ListBinaryTag.empty())
                .put(modern?"block_entities":"TileEntities",ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND,List.of(entity))).build();
            var root=modern?container:CompoundBinaryTag.builder().put("Level",container).build();
            var data=ChunkNbtDecoder.read(stream(root),0,0).chunk().blockEntity(1,0,2).orElseThrow();
            assertEquals("Hello",data.visual().getString("Text1"));assertEquals(false,data.visual().keySet().contains("Items"));
        }
        var large=CompoundBinaryTag.builder().put("sections",ListBinaryTag.empty()).put("block_entities",ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND,java.util.Collections.nCopies(4097,entity))).build();
        assertEquals(ChunkDecodeException.Reason.INVALID_NBT,assertThrows(ChunkDecodeException.class,()->ChunkNbtDecoder.read(stream(large),0,0)).reason());
    }

    @Test
    void preservesModernPropertiesAndStoredCoordinates() throws Exception {
        CompoundBinaryTag state = CompoundBinaryTag.builder()
                .putString("Name", "minecraft:oak_log")
                .put("Properties", CompoundBinaryTag.builder()
                        .putString("waterlogged", "false")
                        .putString("axis", "y")
                        .build())
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .putInt("xPos", -3)
                .putInt("zPos", 7)
                .put("sections", sections(section(0, palette(state), new long[0])))
                .build();

        DecodedChunk decoded = ChunkNbtDecoder.read(stream(root), -3, 7);
        BlockState block = decoded.chunk().blockState(2, 0, 4);

        assertEquals(ChunkStorageLayout.MODERN_ROOT, decoded.layout());
        assertEquals(-3, decoded.chunk().chunkX());
        assertEquals(7, decoded.chunk().chunkZ());
        assertEquals("minecraft:oak_log", block.name());
        assertEquals(Map.of("axis", "y", "waterlogged", "false"), block.properties());
    }

    @Test
    void decodesPaddedStorageAcrossNegativeSection() throws Exception {
        long[] data = new long[256];
        setPadded(data, 4, blockIndex(2, 3, 4), 1);

        VoxelChunk chunk = ChunkNbtDecoder.read(stream(modernChunk(section(
                -2,
                palette(named("minecraft:air"), named("minecraft:deepslate")),
                data)))).chunk();

        assertEquals("minecraft:deepslate", chunk.blockState(2, -29, 4).name());
        assertEquals(BlockState.AIR, chunk.blockState(2, 20, 4));
    }

    @Test
    void decodesLegacyCompactCrossLongStorage() throws Exception {
        List<CompoundBinaryTag> entries = new ArrayList<>();
        entries.add(named("minecraft:air"));
        for (int i = 1; i <= 16; i++) {
            entries.add(named("example:block_" + i));
        }
        long[] data = new long[320];
        setCompact(data, 5, blockIndex(12, 0, 0), 16);
        CompoundBinaryTag oldSection = CompoundBinaryTag.builder()
                .putInt("Y", 4)
                .put("Palette", palette(entries.toArray(CompoundBinaryTag[]::new)))
                .putLongArray("BlockStates", data)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 1)
                        .putInt("zPos", 2)
                        .put("Sections", sections(oldSection))
                        .build())
                .build();

        DecodedChunk decoded = ChunkNbtDecoder.read(stream(root), 1, 2);

        assertEquals(ChunkStorageLayout.LEVEL_PALETTE, decoded.layout());
        assertEquals("example:block_16", decoded.chunk().blockState(12, 64, 0).name());
    }

    @Test
    void rejectsInvalidPropertyStorageAndDuplicateSection() throws IOException {
        CompoundBinaryTag invalidProperty = CompoundBinaryTag.builder()
                .putString("Name", "minecraft:oak_log")
                .put("Properties", CompoundBinaryTag.builder().putInt("axis", 1).build())
                .build();
        ChunkDecodeException propertyFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(modernChunk(
                        section(0, palette(invalidProperty), new long[0])))));
        assertEquals(ChunkDecodeException.Reason.INVALID_PALETTE_PROPERTY,
                propertyFailure.reason());

        CompoundBinaryTag invalidStorage = modernChunk(section(
                0,
                palette(named("minecraft:air"), named("minecraft:stone")),
                new long[1]));
        ChunkDecodeException storageFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(invalidStorage)));
        assertEquals(ChunkDecodeException.Reason.INVALID_BLOCK_STATE_STORAGE,
                storageFailure.reason());

        ChunkDecodeException duplicateFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(modernChunk(
                        section(2, palette(named("minecraft:stone")), new long[0]),
                        section(2, palette(named("minecraft:dirt")), new long[0])))));
        assertEquals(ChunkDecodeException.Reason.INVALID_SECTION, duplicateFailure.reason());
        assertEquals(2, duplicateFailure.sectionY());
    }

    @Test
    void rejectsCoordinateMismatchAndUnsupportedLegacyEmptySections() throws IOException {
        CompoundBinaryTag mismatch = CompoundBinaryTag.builder()
                .putInt("xPos", 4)
                .putInt("zPos", -7)
                .put("sections", sections(section(
                        0,
                        palette(named("minecraft:stone")),
                        new long[0])))
                .build();
        ChunkDecodeException coordinateFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(mismatch), 3, -7));
        assertEquals(ChunkDecodeException.Reason.CHUNK_COORDINATE_MISMATCH,
                coordinateFailure.reason());

        CompoundBinaryTag noBlockStateSection = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .build();
        CompoundBinaryTag unsupportedRoot = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .put("Sections", sections(noBlockStateSection))
                        .build())
                .build();
        ChunkDecodeException unsupportedFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(unsupportedRoot), 0, 0));
        assertEquals(ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                unsupportedFailure.reason());

        CompoundBinaryTag mcrLikeRoot = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .putByteArray("Blocks", new byte[32768])
                        .build())
                .build();
        ChunkDecodeException mcrFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(mcrLikeRoot), 0, 0));
        assertEquals(ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                mcrFailure.reason());
    }

    @Test
    void decodesPreFlatteningLegacySectionsWithNibbleBoundariesAddAndFmlIds() throws Exception {
        byte[] blocks = new byte[4096];
        byte[] data = new byte[2048];
        byte[] add = new byte[2048];

        // Even index (0,0,0): wool (35), meta=14 -> red_wool
        int idx0 = blockIndex(0, 0, 0);
        blocks[idx0] = (byte) 35;
        setNibble(data, idx0, 14);

        // Odd index (1,0,0): FML Mod block ID 412 = (1 << 8) | 156, meta=5
        int idx1 = blockIndex(1, 0, 0);
        blocks[idx1] = (byte) (412 & 0xFF);
        setNibble(add, idx1, 412 >>> 8);
        setNibble(data, idx1, 5);

        // Unmapped high ID 500 = (1 << 8) | 244, meta=3 -> deterministic legacy:block_500
        int idx2 = blockIndex(2, 3, 4);
        blocks[idx2] = (byte) (500 & 0xFF);
        setNibble(add, idx2, 500 >>> 8);
        setNibble(data, idx2, 3);

        // Vanilla grass (2), meta=0 -> grass_block
        int idx3 = blockIndex(3, 0, 0);
        blocks[idx3] = (byte) 2;

        CompoundBinaryTag legacySection = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 4)
                .putByteArray("Blocks", blocks)
                .putByteArray("Data", data)
                .putByteArray("Add", add)
                .build();
        CompoundBinaryTag allAirSection = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 5)
                .putByteArray("Blocks", new byte[4096])
                .putByteArray("Data", new byte[2048])
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", -2)
                        .putInt("zPos", 5)
                        .put("Sections", sections(legacySection, allAirSection))
                        .build())
                .build();

        SavedLegacyIdMap fmlMap = SavedLegacyIdMap.of(
                SavedLegacyIdMap.Source.FML_ITEM_DATA,
                Map.of(412, "Thaumcraft:blockMagicalLog"));

        DecodedChunk sceneDecoded = ChunkNbtDecoder.read(stream(root), -2, 5, fmlMap);
        DecodedChunk surfaceDecoded = ChunkNbtDecoder.readSurface(stream(root), fmlMap);

        assertEquals(ChunkStorageLayout.LEVEL_LEGACY, sceneDecoded.layout());
        assertEquals(ChunkStorageLayout.LEVEL_LEGACY, surfaceDecoded.layout());

        BlockState wool = sceneDecoded.chunk().blockState(0, 64, 0);
        assertEquals("minecraft:red_wool", wool.name());
        assertEquals(Map.of("data", "14"), wool.properties());
        assertEquals("minecraft:red_wool", surfaceDecoded.chunk().blockState(0, 64, 0).name());
        assertTrue(surfaceDecoded.chunk().blockState(0, 64, 0).properties().isEmpty());

        BlockState modBlock = sceneDecoded.chunk().blockState(1, 64, 0);
        assertEquals("Thaumcraft:blockMagicalLog", modBlock.name());
        assertEquals(Map.of("data", "5"), modBlock.properties());
        assertEquals("Thaumcraft:blockMagicalLog", surfaceDecoded.chunk().blockState(1, 64, 0).name());

        BlockState unknownBlock = sceneDecoded.chunk().blockState(2, 67, 4);
        assertEquals("legacy:block_500", unknownBlock.name());
        assertEquals(Map.of("data", "3"), unknownBlock.properties());
        assertEquals("legacy:block_500", surfaceDecoded.chunk().blockState(2, 67, 4).name());

        assertEquals("minecraft:grass_block", sceneDecoded.chunk().blockState(3, 64, 0).name());
        assertEquals(BlockState.AIR, sceneDecoded.chunk().blockState(0, 80, 0));
        assertEquals(BlockState.AIR, sceneDecoded.chunk().sections().get(1).uniformState().orElseThrow());
    }

    @Test
    void rejectsMalformedLegacyArraysAndNonZeroAdd2() {
        CompoundBinaryTag shortBlocks = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", sections(CompoundBinaryTag.builder()
                                .putInt("Y", 0)
                                .putByteArray("Blocks", new byte[100])
                                .putByteArray("Data", new byte[2048])
                                .build()))
                        .build())
                .build();
        assertEquals(ChunkDecodeException.Reason.INVALID_BLOCK_STATE_STORAGE,
                assertThrows(ChunkDecodeException.class, () -> ChunkNbtDecoder.read(stream(shortBlocks))).reason());

        CompoundBinaryTag shortAdd = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", sections(CompoundBinaryTag.builder()
                                .putInt("Y", 0)
                                .putByteArray("Blocks", new byte[4096])
                                .putByteArray("Data", new byte[2048])
                                .putByteArray("Add", new byte[16])
                                .build()))
                        .build())
                .build();
        assertEquals(ChunkDecodeException.Reason.INVALID_BLOCK_STATE_STORAGE,
                assertThrows(ChunkDecodeException.class, () -> ChunkNbtDecoder.read(stream(shortAdd))).reason());

        byte[] add2 = new byte[2048];
        add2[0] = 1;
        CompoundBinaryTag nonZeroAdd2 = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", sections(CompoundBinaryTag.builder()
                                .putInt("Y", 0)
                                .putByteArray("Blocks", new byte[4096])
                                .putByteArray("Data", new byte[2048])
                                .putByteArray("Add2", add2)
                                .build()))
                        .build())
                .build();
        assertEquals(ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                assertThrows(ChunkDecodeException.class, () -> ChunkNbtDecoder.read(stream(nonZeroAdd2))).reason());
    }

    private static void setNibble(byte[] array, int index, int value) {
        int byteIndex = index >>> 1;
        if ((index & 1) == 0) {
            array[byteIndex] = (byte) ((array[byteIndex] & 0xF0) | (value & 0x0F));
        } else {
            array[byteIndex] = (byte) ((array[byteIndex] & 0x0F) | ((value & 0x0F) << 4));
        }
    }

    private static CompoundBinaryTag named(String name) {
        return CompoundBinaryTag.builder().putString("Name", name).build();
    }

    private static ListBinaryTag palette(CompoundBinaryTag... entries) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder =
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag entry : entries) {
            builder.add(entry);
        }
        return builder.build();
    }

    @Test void decodesLegacy115To117Int1024BiomesAndUnknownIdsDegradeWithoutLosingGeometry() throws Exception {
        int[] biomes = new int[1024];
        java.util.Arrays.fill(biomes, 1); // plains
        // Section Y=4 (globalQy = 16..19), local qy=1 (globalQy=17), qz=2, qx=3 -> jungle (21)
        biomes[((17 << 2) | 2) << 2 | 3] = 21;
        // Section Y=5 has an unknown numeric biome ID 250
        biomes[(20 << 4)] = 250;

        CompoundBinaryTag sec4 = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 4)
                .put("Palette", palette(named("minecraft:grass_block")))
                .putLongArray("BlockStates", new long[0])
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .putInt("DataVersion", 2586)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0).putInt("zPos", 0)
                        .put("Sections", sections(sec4))
                        .putIntArray("Biomes", biomes)
                        .build())
                .build();

        VoxelChunk chunk = ChunkNbtDecoder.read(stream(root), 0, 0).chunk();
        assertEquals("minecraft:grass_block", chunk.blockState(0, 64, 0).name());
        assertEquals(java.util.Optional.of("minecraft:plains"), chunk.biomeAtQuart(0, 16, 0));
        assertEquals(java.util.Optional.of("minecraft:jungle"), chunk.biomeAtQuart(3, 17, 2));
        assertEquals(com.mcworldexplorer.voxel.data.BiomeSection.Status.INVALID, chunk.biomeSection(5).orElseThrow().status());
        assertTrue(chunk.biomeAtQuart(0, 20, 0).isEmpty());
    }

    @Test void decodesLegacyInt256AndByte256BiomesAndMalformedBiomesKeepGeometry() throws Exception {
        int[] biomesInt256 = new int[256];
        java.util.Arrays.fill(biomesInt256, 4); // forest
        biomesInt256[8 * 16 + 12] = 6; // qz=2, qx=3 -> swamp
        CompoundBinaryTag sec = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 4)
                .put("Palette", palette(named("minecraft:stone")))
                .putLongArray("BlockStates", new long[0])
                .build();
        CompoundBinaryTag root113 = CompoundBinaryTag.builder()
                .putInt("DataVersion", 1631)
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0).putInt("zPos", 0)
                        .put("Sections", sections(sec))
                        .putIntArray("Biomes", biomesInt256)
                        .build())
                .build();
        VoxelChunk chunk113 = ChunkNbtDecoder.read(stream(root113), 0, 0).chunk();
        assertEquals(java.util.Optional.of("minecraft:forest"), chunk113.biomeAtQuart(0, 16, 0));
        assertEquals(java.util.Optional.of("minecraft:swamp"), chunk113.biomeAtQuart(3, 19, 2));

        // byte[256] with unsigned ID 129 (sunflower_plains)
        byte[] biomesByte256 = new byte[256];
        java.util.Arrays.fill(biomesByte256, (byte) 129);
        byte[] blocks = new byte[4096];
        blocks[0] = 1; // stone
        CompoundBinaryTag legacySec = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 0)
                .putByteArray("Blocks", blocks)
                .putByteArray("Data", new byte[2048])
                .build();
        CompoundBinaryTag root112 = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0).putInt("zPos", 0)
                        .put("Sections", sections(legacySec))
                        .putByteArray("Biomes", biomesByte256)
                        .build())
                .build();
        VoxelChunk chunk112 = ChunkNbtDecoder.read(stream(root112), 0, 0).chunk();
        assertEquals("minecraft:stone", chunk112.blockState(0, 0, 0).name());
        assertEquals(java.util.Optional.of("minecraft:sunflower_plains"), chunk112.biomeAtQuart(0, 0, 0));

        // Malformed Biomes length preserves block geometry
        CompoundBinaryTag malformedRoot = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0).putInt("zPos", 0)
                        .put("Sections", sections(sec))
                        .putIntArray("Biomes", new int[100])
                        .build())
                .build();
        VoxelChunk malformedChunk = ChunkNbtDecoder.read(stream(malformedRoot), 0, 0).chunk();
        assertEquals("minecraft:stone", malformedChunk.blockState(0, 64, 0).name());
        assertEquals(com.mcworldexplorer.voxel.data.BiomeSection.Status.INVALID, malformedChunk.biomeSection(4).orElseThrow().status());
    }

    private static ListBinaryTag sections(CompoundBinaryTag... entries) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder =
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag entry : entries) {
            builder.add(entry);
        }
        return builder.build();
    }

    private static CompoundBinaryTag section(int y, ListBinaryTag palette, long[] data) {
        CompoundBinaryTag.Builder states = CompoundBinaryTag.builder().put("palette", palette);
        if (data.length > 0) {
            states.putLongArray("data", data);
        }
        return CompoundBinaryTag.builder()
                .putInt("Y", y)
                .put("block_states", states.build())
                .build();
    }

    private static CompoundBinaryTag modernChunk(CompoundBinaryTag... entries) {
        return CompoundBinaryTag.builder().put("sections", sections(entries)).build();
    }

    private static ByteArrayInputStream stream(CompoundBinaryTag root) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(root, output, BinaryTagIO.Compression.NONE);
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static int blockIndex(int x, int y, int z) {
        return y * 256 + z * 16 + x;
    }

    private static void setPadded(long[] data, int bits, int index, int value) {
        int valuesPerLong = Long.SIZE / bits;
        int longIndex = index / valuesPerLong;
        int bitOffset = index % valuesPerLong * bits;
        data[longIndex] |= (long) value << bitOffset;
    }

    private static void setCompact(long[] data, int bits, int index, int value) {
        long bitIndex = (long) index * bits;
        int longIndex = (int) (bitIndex / Long.SIZE);
        int bitOffset = (int) (bitIndex % Long.SIZE);
        data[longIndex] |= (long) value << bitOffset;
        if (bitOffset + bits > Long.SIZE) {
            data[longIndex + 1] |= (long) value >>> (Long.SIZE - bitOffset);
        }
    }
}
