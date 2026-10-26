package com.mcworldexplorer.voxel.resource;

import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical asset identity. Never interprets an ID as a host filesystem path. */
public record ResourceLocation(String namespace, String path) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    public ResourceLocation {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.length() > 64 || path.length() > 512
                || !NAMESPACE.matcher(namespace).matches() || namespace.equals(".") || namespace.equals("..")
                || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("资源 ID 格式无效");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("资源 ID 不能包含空路径或相对路径段");
        }
    }

    public static ResourceLocation parse(String value) {
        Objects.requireNonNull(value, "value");
        int colon = value.indexOf(':');
        return colon < 0 ? new ResourceLocation("minecraft", value)
                : new ResourceLocation(value.substring(0, colon), value.substring(colon + 1));
    }

    public enum Kind {
        BLOCKSTATE("blockstates/", ".json", 1024 * 1024),
        MODEL("models/", ".json", 1024 * 1024),
        TEXTURE("textures/", ".png", 8 * 1024 * 1024),
        TEXTURE_META("textures/", ".png.mcmeta", 64*1024),
        RAW_ASSET("", "", 4*1024*1024),
        BANNER_PATTERN("banner_pattern/", ".json", 64*1024);

        private final String folder;
        private final String extension;
        private final int maxBytes;

        Kind(String folder, String extension, int maxBytes) {
            this.folder = folder;
            this.extension = extension;
            this.maxBytes = maxBytes;
        }

        public int maxBytes() { return maxBytes; }
    }

    public String entryName(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return (kind==Kind.BANNER_PATTERN?"data/":"assets/") + namespace + "/" + kind.folder + path + kind.extension;
    }

    @Override public String toString() { return namespace + ":" + path; }
}
