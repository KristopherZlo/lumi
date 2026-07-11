package io.github.luma.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowingFluidMixinTest {

    @Test
    void suppressesStaleReplayFluidTicksBeforeTheyRecordFallout() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/FlowingFluidMixin.java"));

        assertTrue(source.contains("WorldReplayTickSuppression"));
        assertTrue(source.contains("LUMA_REPLAY_TICK_SUPPRESSION.shouldSuppressCallback(level, pos)"));
        assertTrue(source.contains("HistoryDebugLog"));
        assertTrue(source.contains("logFluidTick"));
    }
}
