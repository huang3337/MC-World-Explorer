package com.mcworldexplorer.voxel.resource;

import java.io.IOException;
import java.util.Optional;

/** Read-only asset lookup boundary, independent of client, Mod or pack containers. */
public interface BlockResourceSource {
    Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id) throws IOException;
    Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id) throws IOException;
    ClientResourceArchive.Fingerprint fingerprint();
    /** Recheck mutable backing sources before/after using cached parsed resources. */
    default void validate() throws IOException { }
}
