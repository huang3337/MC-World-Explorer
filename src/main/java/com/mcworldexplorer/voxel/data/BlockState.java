package com.mcworldexplorer.voxel.data;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record BlockState(String name, Map<String, String> properties, SavedForm savedForm) {
    public static final BlockState AIR = new BlockState("minecraft:air", Map.of());

    /** Original fields retained across default expansion and derived visual states. */
    public record SavedForm(int dataVersion, Map<String, String> written, boolean defaultsKnown) {
        public SavedForm {
            written = Map.copyOf(written);
        }
    }

    public BlockState(String name, Map<String, String> properties) {
        this(name, properties, null);
    }

    public BlockState withProperties(Map<String, String> derived) {
        if (properties.equals(derived)) return this;
        var original = savedForm == null ? new SavedForm(-1, properties, true) : savedForm;
        return new BlockState(name, derived, original);
    }

    public Map<String, String> savedProperties() {
        return savedForm == null ? properties : savedForm.written();
    }

    private long unresolvedVersion() {
        return savedForm != null && !savedForm.defaultsKnown() ? savedForm.dataVersion() : Long.MIN_VALUE;
    }

    // Equivalent effective states share mesh and texture budgets across palette encodings.
    @Override public boolean equals(Object other) {
        return this == other || other instanceof BlockState that && name.equals(that.name)
                && properties.equals(that.properties) && unresolvedVersion() == that.unresolvedVersion();
    }

    @Override public int hashCode() {
        return 31 * (31 * name.hashCode() + properties.hashCode()) + Long.hashCode(unresolvedVersion());
    }

    public BlockState {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(properties, "properties");
        if (!properties.isEmpty()) {
            properties = sortedProperties(properties);
        } else {
            properties = Map.of();
        }
    }

    private static Map<String, String> sortedProperties(Map<String, String> properties) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("property names and values must not be blank");
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        return new SortedProperties(sorted);
    }

    /** Area preparation and meshing repeatedly look up the same immutable palette states. */
    private static final class SortedProperties extends java.util.AbstractMap<String,String> {
        private final Map<String,String> values;
        private final int hash;
        private final Map.Entry<String,String> single;
        SortedProperties(TreeMap<String,String> sorted) {
            single=sorted.size()==1?sorted.firstEntry():null;
            values=single==null?Collections.unmodifiableMap(sorted):Map.of(single.getKey(),single.getValue());
            hash=values.hashCode();
        }
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){
            if(this==other)return true;
            if(other instanceof SortedProperties that){
                if(hash!=that.hash || size()!=that.size())return false;
                if(single!=null)return single.equals(that.single);
            }
            return values.equals(other);
        }
        @Override public int size(){return values.size();}
        @Override public String get(Object key){return values.get(key);}
        @Override public boolean containsKey(Object key){return values.containsKey(key);}
        @Override public java.util.Set<Entry<String,String>> entrySet(){return values.entrySet();}
    }
}
