package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Deduplicates per-position tint palettes in a bounded sparse section index without cloning BlockStates. */
final class BlockTintPreparation {
    private static final int MAX_SECTIONS = 2048; // at most 16 MiB of position indices
    private static final int MAX_PALETTES = 65535;
    private static final int MAX_NOTES_PER_STATE = 8;

    private record Section(long x, int y, long z) { }

    private static final class TintPalette {
        private final int[] indices;
        private final int[] rgb;

        private TintPalette(Map<Integer, Integer> values) {
            var sorted = new TreeMap<>(values);
            this.indices = new int[sorted.size()];
            this.rgb = new int[sorted.size()];
            int i = 0;
            for (var entry : sorted.entrySet()) {
                this.indices[i] = entry.getKey();
                this.rgb[i] = entry.getValue() & 0xFFFFFF;
                i++;
            }
        }

        private int color(int tintIndex, int fallback) {
            for (int i = 0; i < indices.length; i++) {
                if (indices[i] == tintIndex) return rgb[i];
            }
            return fallback;
        }
    }

    private final Map<Section, short[]> sections = new HashMap<>();
    private final List<TintPalette> palettes = new ArrayList<>();
    private final Map<Map<Integer, Integer>, Integer> paletteIds = new HashMap<>();
    private final Map<BlockState, Set<Integer>> resolvedDynamicIndices = new HashMap<>();

    boolean isDynamicIndex(BlockState state, int tintIndex) {
        var indices = resolvedDynamicIndices.get(state);
        return indices != null && indices.contains(tintIndex);
    }

    int color(long x, int y, long z, int tintIndex, int fallback) {
        if (tintIndex < 0 || sections.isEmpty()) return fallback;
        var values = sections.get(new Section(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16)));
        if (values == null) return fallback;
        int id = Short.toUnsignedInt(values[(Math.floorMod(y, 16) * 16 + Math.floorMod(z, 16)) * 16 + (int) Math.floorMod(x, 16)]);
        if (id == 0) return fallback;
        return palettes.get(id - 1).color(tintIndex, fallback);
    }

    private void recordResolvedIndices(BlockState state, Set<Integer> indices) {
        resolvedDynamicIndices.computeIfAbsent(state, ignored -> new HashSet<>()).addAll(indices);
    }

    private boolean store(long x, int y, long z, Map<Integer, Integer> tintValues) {
        var section = new Section(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
        if (!sections.containsKey(section) && sections.size() >= MAX_SECTIONS) return false;
        Integer id = paletteIds.get(tintValues);
        if (id == null) {
            if (palettes.size() >= MAX_PALETTES) return false;
            var palette = new TintPalette(tintValues);
            palettes.add(palette);
            id = palettes.size();
            paletteIds.put(Map.copyOf(tintValues), id);
        }
        sections.computeIfAbsent(section, key -> new short[4096])[(Math.floorMod(y, 16) * 16 + Math.floorMod(z, 16)) * 16 + (int) Math.floorMod(x, 16)] = (short) id.intValue();
        return true;
    }

    static BlockTintPreparation prepare(BuiltinBlockModels source, VoxelAreaLoadResult area, CancellationSignal cancel,
            Set<BlockState> states, Map<BlockState, Result> resolved, Map<BlockState, Dependencies> dependencies,
            AreaModelVariants weighted, ModelRuleRegistry rules) throws IOException {
        var output = new BlockTintPreparation();
        var colors = new BlockTintResolver(source, rules);
        var contextual = new HashSet<String>();
        for (var entry : resolved.entrySet()) {
            if (!entry.getValue().resolved() || !BlockTintResolver.indexed(entry.getValue().model().orElseThrow())) continue;
            var model = entry.getValue().model().orElseThrow();
            var dynamic = colors.dynamicBoundIndices(entry.getKey(), model);
            if (!dynamic.isEmpty()) {
                contextual.add(entry.getKey().name());
                output.recordResolvedIndices(entry.getKey(), dynamic);
                var staticTint = colors.staticColors(entry.getKey(), model);
                if (!staticTint.values().isEmpty() || !staticTint.detail().isBlank()) {
                    entry.setValue(BlockTintResolver.apply(entry.getValue(), staticTint));
                    note(source, entry.getKey(), staticTint.detail());
                }
                continue;
            }
            var tint = colors.colors(entry.getKey(), model, null);
            entry.setValue(BlockTintResolver.apply(entry.getValue(), tint));
            note(source, entry.getKey(), tint.detail());
        }
        if (contextual.isEmpty()) return output;
        Map<BlockState, Set<String>> recordedDetails = new HashMap<>();
        var target = area.target();
        for (var coordinate : area.results().keySet()) {
            if (!target.contains(coordinate)) continue;
            var loaded = area.result(coordinate);
            if (loaded.chunk() == null) continue;
            try {
                for (var section : loaded.chunk().sections()) {
                    if (!section.containsAny(contextual)) continue;
                    for (int y = 0; y < 16; y++) {
                        if (cancel.isCancelled() || Thread.currentThread().isInterrupted())
                            throw new java.util.concurrent.CancellationException("颜色准备已取消");
                        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var physical = section.blockState(x, y, z);
                            if (!contextual.contains(physical.name())) continue;
                            long wx = (long) coordinate.x() * 16 + x, wz = (long) coordinate.z() * 16 + z;
                            int wy = section.sectionY() * 16 + y;

                            var state = BlockTextureSet.visualState(physical, loaded.chunk().blockEntity(wx, wy, wz).orElse(null));
                            state = weighted.state(state, wx, wy, wz);
                            var context = new ModelBlockContext(area, new ModelBlockContext.Position(wx, wy, wz), cancel);
                            var dependency = dependencies.get(state);
                            if (dependency != null) state = BlockTextureSet.contextKey(state, context, dependency);
                            var original = resolved.get(state);
                            if (original == null || !original.resolved()) continue;
                            var tint = colors.colors(state, original.model().orElseThrow(), context);
                            if (!tint.detail().isBlank()) {
                                var seen = recordedDetails.computeIfAbsent(state, ignored -> new HashSet<>());
                                if ((seen.size() < MAX_NOTES_PER_STATE || tint.detail().contains("颜色未解析")) && seen.add(tint.detail())) {
                                    note(source, state, tint.detail());
                                }
                            }
                            if (tint.values().isEmpty()) {
                                continue;
                            }
                            output.recordResolvedIndices(state, tint.values().keySet());
                            if (!output.store(wx, wy, wz, tint.values())) {
                                String overflow = "颜色未解析：保存位置颜色索引超过准备预算；保留未染色几何";
                                if (recordedDetails.computeIfAbsent(state, ignored -> new HashSet<>()).add(overflow)) {
                                    note(source, state, overflow);
                                }
                            }
                        }
                    }
                }
            } catch (ChunkDecodeException corrupt) { /* Existing mesher reports the unavailable section. */ }
        }
        return output;
    }

    static void note(BuiltinBlockModels source, BlockState state, String text) {
        if (!text.isBlank()) {
            var properties = new TreeMap<>(state.properties());
            properties.remove("__mcwe_context");
            source.note(state.withProperties(properties), text);
        }
    }
}
