package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void reconciledFluidFallbackChangesUseWiderRelatedActionWindow() {
        StoredBlockChange fluidChange = new StoredBlockChange(
                new BlockPoint(0, 64, 0),
                state("minecraft:air"),
                state("minecraft:water")
        );
        StoredBlockChange ordinaryChange = new StoredBlockChange(
                new BlockPoint(1, 64, 0),
                state("minecraft:air"),
                state("minecraft:stone")
        );

        assertEquals(Duration.ofSeconds(60), LiveUndoRedoActionRecorder.relatedJoinWindowFor(fluidChange));
        assertEquals(8, LiveUndoRedoActionRecorder.relatedJoinRadiusFor(fluidChange));
        assertEquals(Duration.ofSeconds(10), LiveUndoRedoActionRecorder.relatedJoinWindowFor(ordinaryChange));
        assertEquals(2, LiveUndoRedoActionRecorder.relatedJoinRadiusFor(ordinaryChange));
    }

    @Test
    void hiddenCausalBlockChangesRemainRecordableForUndoRedo() {
        StoredBlockChange hiddenFallout = new StoredBlockChange(
                new BlockPoint(0, 64, 0),
                state("minecraft:water"),
                state("minecraft:cobblestone"),
                true
        );
        StoredBlockChange noOp = new StoredBlockChange(
                new BlockPoint(1, 64, 0),
                state("minecraft:stone"),
                state("minecraft:stone"),
                true
        );
        List<StoredBlockChange> changes = new ArrayList<>();
        changes.add(null);
        changes.add(hiddenFallout);
        changes.add(noOp);

        List<StoredBlockChange> recordable = LiveUndoRedoActionRecorder.recordableBlockChanges(changes);

        assertEquals(List.of(hiddenFallout), recordable);
        assertTrue(recordable.getFirst().hidden());
    }

    @Test
    void hiddenGrowthChangesRecordImmediatelyForUndoRedo() {
        StoredBlockChange hiddenGrowth = new StoredBlockChange(
                new BlockPoint(0, 64, 0),
                state("minecraft:moss_block"),
                state("minecraft:azalea"),
                true
        );

        assertFalse(LiveUndoRedoActionRecorder.defersImmediateCausalChange(
                WorldMutationSource.GROWTH,
                hiddenGrowth
        ));
    }

    private static StatePayload state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
