package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiSelectionTargetingTest {
    @Test
    void reachesOneHundredBlocksAtFourChunkRenderDistance() {
        assertTrue(LumiSelectionTool.targetDistance(4) > 100.0);
    }

    @Test
    void raycastsAcrossLoadedRenderDistanceWithoutRequestingAChunk() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiSelectionTool.java"));

        assertTrue(source.contains("getEffectiveRenderDistance()"));
        assertTrue(source.contains("client.level.clip(new ClipContext("));
        assertTrue(!source.contains("client.hitResult"));
        assertTrue(source.contains("client.level.hasChunkAt(position)"));
        assertTrue(source.contains("client.level.getBlockState(position).isAir()"));
        assertTrue(source.indexOf("hasChunkAt(position)")
                < source.indexOf("getBlockState(position)"));
        assertTrue(!source.contains("getChunk(position"));
    }
}
