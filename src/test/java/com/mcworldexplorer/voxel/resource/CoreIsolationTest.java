package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreIsolationTest {
    @Test void unadmittedLoadersKeepTheirIdentityAndFailExplicitly() throws Exception {
        assertEquals(List.of(ResourceLocation.parse("mcwe:obj")),
                ModelRuleRegistry.defaults().rules().stream().map(ModelRule::id).toList());
        for (String loader : List.of("forge:composite", "neoforge:elements", "forge:empty",
                "forge:separate_transforms", "twilightforest:patch", "framework:open_model",
                "supplementaries:jar", "athena:ctm", "future:unknown")) {
            var source = new BlockResourceSource() {
                public Optional<byte[]> read(ResourceLocation.Kind kind, ResourceLocation id) { return Optional.empty(); }
                public Optional<String> readJson(ResourceLocation.Kind kind, ResourceLocation id) {
                    return switch (kind) {
                        case BLOCKSTATE -> Optional.of("{\"variants\":{\"\":{\"model\":\"original:shape\"}}}");
                        case MODEL -> Optional.of("{\"loader\":\"" + loader + "\"}");
                        default -> Optional.empty();
                    };
                }
                public ClientResourceArchive.Fingerprint fingerprint() {
                    return new ClientResourceArchive.Fingerprint(Path.of("synthetic-core-test"), 0, 0, "synthetic");
                }
            };
            var result = new BlockModelResolver(source).resolve(new BlockState("original:shape", Map.of()));
            assertEquals(BlockModelResolver.Reason.UNSUPPORTED_MODEL, result.reason(), loader);
            assertTrue(result.detail().contains(loader), result.detail());
            assertTrue(result.model().isEmpty());
            assertTrue(result.sources().stream().anyMatch(s -> s.id().toString().equals("original:shape")));
        }
    }
}
