package com.mcworldexplorer.voxel.data;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record BlockState(String name, Map<String, String> properties) {
    public static final BlockState AIR = new BlockState("minecraft:air", Map.of());

    public BlockState {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(properties, "properties");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("property names and values must not be blank");
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        properties = Collections.unmodifiableMap(sorted);
    }
}
