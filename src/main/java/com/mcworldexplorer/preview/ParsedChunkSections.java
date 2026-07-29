package com.mcworldexplorer.preview;

import com.mcworldexplorer.region.RegionChunkData;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ParsedChunkSections {
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    private static final long MAX_CHUNK_NBT_BYTES = 64L * 1024 * 1024;
    private static final BinaryTagIO.Reader CHUNK_READER = BinaryTagIO.reader(MAX_CHUNK_NBT_BYTES);

    private final ChunkSurfaceLayout layout;
    private final List<ChunkSectionView> sections;
    private final Map<Integer, ChunkSectionView> sectionsByY;

    private ParsedChunkSections(
            ChunkSurfaceLayout layout,
            List<ChunkSectionView> sections) {
        this.layout = layout;
        this.sections = List.copyOf(sections);
        Map<Integer, ChunkSectionView> byY = new HashMap<>();
        for (ChunkSectionView section : sections) {
            byY.put(section.sectionY(), section);
        }
        sectionsByY = Map.copyOf(byY);
    }

    public static ParsedChunkSections read(RegionChunkData chunkData)
            throws SurfaceSamplingException {
        if (chunkData == null) {
            throw new IllegalArgumentException("chunkData must not be null");
        }
        try (InputStream input = chunkData.openNbtStream()) {
            return read(input);
        } catch (SurfaceSamplingException e) {
            throw e;
        } catch (IOException e) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_NBT,
                    "failed to close chunk NBT input",
                    e);
        }
    }

    static ParsedChunkSections read(InputStream input) throws SurfaceSamplingException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        CompoundBinaryTag root;
        try {
            root = CHUNK_READER.read(input, BinaryTagIO.Compression.NONE);
        } catch (IOException | RuntimeException e) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_NBT,
                    "failed to parse chunk NBT",
                    e);
        }

        ChunkSurfaceLayout layout;
        ListBinaryTag sectionsTag;
        if (root.keySet().contains("sections")) {
            layout = ChunkSurfaceLayout.MODERN_ROOT;
            sectionsTag = root.getList("sections", BinaryTagTypes.COMPOUND);
        } else {
            CompoundBinaryTag level = root.getCompound("Level");
            if (!level.keySet().contains("Sections")) {
                throw new SurfaceSamplingException(
                        SurfaceSamplingException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                        "chunk has neither root sections nor Level/Sections palette data");
            }
            layout = ChunkSurfaceLayout.LEVEL_PALETTE;
            sectionsTag = level.getList("Sections", BinaryTagTypes.COMPOUND);
        }

        List<ChunkSectionView> sections = new ArrayList<>();
        for (int i = 0; i < sectionsTag.size(); i++) {
            ChunkSectionView section = readSection(sectionsTag.getCompound(i), layout);
            if (section != null) {
                sections.add(section);
            }
        }
        return new ParsedChunkSections(layout, sections);
    }

    public ChunkSurfaceLayout layout() {
        return layout;
    }

    public List<ChunkSectionView> sections() {
        return sections;
    }

    public Optional<DimensionHeightRange> sectionRange() {
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        int minSectionY = sections.stream()
                .mapToInt(ChunkSectionView::sectionY)
                .min()
                .orElseThrow();
        int maxSectionY = sections.stream()
                .mapToInt(ChunkSectionView::sectionY)
                .max()
                .orElseThrow();
        return Optional.of(new DimensionHeightRange(
                Math.multiplyExact(minSectionY, 16),
                Math.addExact(Math.multiplyExact(maxSectionY, 16), 15)));
    }

    public String blockName(int localX, int y, int localZ)
            throws SurfaceSamplingException {
        ChunkSectionView section = sectionsByY.get(Math.floorDiv(y, 16));
        return section == null
                ? "minecraft:air"
                : section.blockName(localX, Math.floorMod(y, 16), localZ);
    }

    private static ChunkSectionView readSection(
            CompoundBinaryTag section,
            ChunkSurfaceLayout layout) throws SurfaceSamplingException {
        if (!section.keySet().contains("Y")) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_SECTION,
                    "section is missing Y");
        }

        ListBinaryTag palette;
        long[] blockStates;
        if (layout == ChunkSurfaceLayout.MODERN_ROOT) {
            CompoundBinaryTag stateContainer = section.getCompound("block_states");
            if (stateContainer.keySet().isEmpty()) {
                return null;
            }
            palette = stateContainer.getList("palette", BinaryTagTypes.COMPOUND);
            blockStates = stateContainer.getLongArray("data");
        } else {
            if (!section.keySet().contains("Palette")) {
                return null;
            }
            palette = section.getList("Palette", BinaryTagTypes.COMPOUND);
            blockStates = section.getLongArray("BlockStates");
        }

        if (palette.size() == 0 || palette.size() > BLOCKS_PER_SECTION) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_PALETTE,
                    "section palette size is outside 1.." + BLOCKS_PER_SECTION);
        }
        String[] names = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompound(i).getString("Name");
            if (name == null || name.isBlank()) {
                throw new SurfaceSamplingException(
                        SurfaceSamplingException.Reason.INVALID_PALETTE,
                        "palette entry " + i + " has no block name");
            }
            names[i] = name;
        }
        return new ChunkSectionView(section.getInt("Y"), names, blockStates);
    }
}
