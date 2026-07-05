package io.github.luma.minecraft.world;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldReplayTickSuppressionTest {

    @Test
    void replaySuppressionOwnsWorldTickFreezeLifecycle() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/world/WorldReplayTickSuppression.java"));
        String manager = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/world/WorldOperationManager.java"));

        assertTrue(source.contains("freezeWorldTick(ServerLevel level)"));
        assertTrue(source.contains("releaseWorldTickFreeze(ServerLevel level)"));
        assertTrue(source.contains("shouldFreezeWorldTick(ServerLevel level)"));
        assertFalse(source.contains("logFrozenWorldTick(ServerLevel level)"));
        assertTrue(source.contains("LumaLoadLog.event(\"tnt-replay\", \"freeze-acquire\""));
        assertTrue(source.contains("LumaLoadLog.event(\"tnt-replay\", \"freeze-release\""));
        assertTrue(source.contains("LumaDiagnosticsLog.fluidUndoEvent(\"replay-protect\""));
        assertTrue(source.contains("LumaDiagnosticsLog.fluidUndoEvent(\"replay-suppress\""));
        assertFalse(source.contains("LumaLoadLog.event(\"tnt-replay\", \"world-tick-cancelled\""));
        assertTrue(manager.contains("startPreparedApplyOperation("));
        assertTrue(manager.contains("boolean freezeWorldTicks"));
        assertTrue(manager.contains("releaseWorldTickFreeze"));
    }
}
