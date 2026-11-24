package com.mcworldexplorer.map;

import com.mcworldexplorer.nbt.SavedLegacyIdMap;
import com.mcworldexplorer.preview.ParsedChunkSections;
import com.mcworldexplorer.preview.PreviewLayer;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyPortalMarkerTest {

    @Test
    void extractsNetherPortalMarkerFromPreFlatteningLegacySection() throws IOException {
        byte[] blocks = new byte[4096];
        byte[] data = new byte[2048];
        // Block ID 90 = minecraft:portal (normalized to minecraft:nether_portal)
        blocks[(4 * 256) + (2 * 16) + 3] = 90;
        blocks[(5 * 256) + (2 * 16) + 3] = 90;

        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putByte("Y", (byte) 4)
                .putByteArray("Blocks", blocks)
                .putByteArray("Data", data)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 1)
                        .putInt("zPos", -2)
                        .put("Sections", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(section).build())
                        .build())
                .build();

        ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(root, nbtBytes, BinaryTagIO.Compression.NONE);

        ParsedChunkSections parsed = ParsedChunkSections.read(
                new ByteArrayInputStream(nbtBytes.toByteArray()),
                SavedLegacyIdMap.vanillaOnly());
        List<MapMarker> markers = new PortalMarkerExtractor().extract(
                parsed, PreviewLayer.surfaceOverview(), "minecraft:overworld", 1, -2);

        assertEquals(1, markers.size());
        assertEquals(MapMarkerType.NETHER_PORTAL, markers.getFirst().type());
        assertEquals(19, markers.getFirst().x());
        assertEquals(-30, markers.getFirst().z());
    }
}
