package com.mcworldexplorer.experimental.v05;

import com.mcworldexplorer.voxel.data.ChunkRectangle;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class V05ValidationPipelineIntegrationTest {
    @Test
    void preparesConfiguredRealDimensionWithRenderableGeometry() throws Exception {
        String world = System.getenv("MCWORLD_V05_PIPELINE_WORLD");
        String dimension = System.getenv("MCWORLD_V05_PIPELINE_DIMENSION");
        assumeTrue(world != null && !world.isBlank());
        assumeTrue(dimension != null && !dimension.isBlank());
        int centerX = integerEnvironment("MCWORLD_V05_PIPELINE_CHUNK_X", 0);
        int centerZ = integerEnvironment("MCWORLD_V05_PIPELINE_CHUNK_Z", 0);

        V05Arguments arguments = new V05Arguments(
                Path.of(world).toRealPath(),
                dimension,
                ChunkRectangle.centered(centerX, centerZ, 3, 3),
                Optional.empty(),
                Optional.empty(),
                1);
        V05ValidationContext context = new V05ValidationPipeline().prepare(arguments);

        assertTrue(context.sceneResult().snapshot().orElseThrow().faceCount() > 0);
        System.out.printf("V0.5 pipeline sample: dimension=%s center=%d,%d blocks=%d faces=%d%n",
                dimension,
                centerX,
                centerZ,
                context.sceneResult().snapshot().orElseThrow().blockCount(),
                context.sceneResult().snapshot().orElseThrow().faceCount());
    }

    private static int integerEnvironment(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }
}
