package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.voxel.data.BlockState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Offline registry facts indexed by the chunk's saved DataVersion. No model or loader dependencies. */
final class VanillaBlockDefaults {
    private record Definition(Set<Integer> versions, Map<String, String> defaults) {}

    private static final class Catalog {
        static final Map<String, List<Definition>> BLOCKS = load();
    }

    private VanillaBlockDefaults() {}

    static BlockState resolve(String name, Map<String, String> written, int version) {
        if (!name.startsWith("minecraft:")) return new BlockState(name, written);
        var candidates = Catalog.BLOCKS.get(name);
        if (candidates != null) {
            for (var candidate : candidates) {
                if (candidate.versions().contains(version)) {
                    var defaults = candidate.defaults();
                    if (written.keySet().containsAll(defaults.keySet())) {
                        return new BlockState(name, written);
                    }
                    Map<String, String> effective = defaults;
                    if (!written.isEmpty()) {
                        effective = new HashMap<>(defaults);
                        effective.putAll(written);
                    }
                    return new BlockState(name, effective, new BlockState.SavedForm(version, written, true));
                }
            }
        }
        // Resource selectors can still use explicitly written keys without consulting defaults.
        return new BlockState(name, written, new BlockState.SavedForm(version, written, false));
    }

    private static Map<String, List<Definition>> load() {
        var stream = VanillaBlockDefaults.class.getResourceAsStream("/vanilla-protocol/block-defaults.tsv");
        if (stream == null) throw new IllegalStateException("缺少内置原版方块状态数据");
        var result = new HashMap<String, List<Definition>>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 3) throw new IOException("方块默认状态表列数错误");
                var versions = Arrays.stream(fields[0].split(",")).map(Integer::valueOf).collect(Collectors.toUnmodifiableSet());
                var properties = new HashMap<String, String>();
                if (!fields[1].isEmpty()) {
                    for (String assignment : fields[1].split(",")) {
                        String[] pair = assignment.split("=", 2);
                        if (pair.length != 2 || properties.putIfAbsent(pair[0], pair[1]) != null) {
                            throw new IOException("方块默认属性格式错误");
                        }
                    }
                }
                var definition = new Definition(versions, Map.copyOf(properties));
                for (String id : fields[2].split(",")) {
                    result.computeIfAbsent("minecraft:" + id, key -> new ArrayList<>()).add(definition);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取内置原版方块状态数据", e);
        }
        result.replaceAll((id, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }
}
