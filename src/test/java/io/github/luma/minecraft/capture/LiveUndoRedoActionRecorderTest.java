package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveUndoRedoActionRecorderTest {

    @Test
    void spreadingFalloutUsesWiderRelatedActionWindow() {
        assertEquals(Duration.ofSeconds(60), LiveUndoRedoActionRecorder.relatedJoinWindowFor(WorldMutationSource.FLUID));
        assertEquals(Duration.ofSeconds(60), LiveUndoRedoActionRecorder.relatedJoinWindowFor(WorldMutationSource.FALLING_BLOCK));
        assertEquals(8, LiveUndoRedoActionRecorder.relatedJoinRadiusFor(WorldMutationSource.FLUID));
        assertEquals(8, LiveUndoRedoActionRecorder.relatedJoinRadiusFor(WorldMutationSource.FALLING_BLOCK));
    }

    @Test
    void ordinarySecondarySourcesKeepTightRelatedActionWindow() {
        assertEquals(Duration.ofSeconds(10), LiveUndoRedoActionRecorder.relatedJoinWindowFor(WorldMutationSource.BLOCK_UPDATE));
        assertEquals(2, LiveUndoRedoActionRecorder.relatedJoinRadiusFor(WorldMutationSource.BLOCK_UPDATE));
    }
}
