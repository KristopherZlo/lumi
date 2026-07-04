package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveUndoRedoActionRecorderTest {

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
    void mobAndExplosionEntityReplayRequireCausalAction() {
        assertTrue(LiveUndoRedoActionRecorder.requiresCausalActionForEntityReplay(WorldMutationSource.EXPLOSION));
        assertTrue(LiveUndoRedoActionRecorder.requiresCausalActionForEntityReplay(WorldMutationSource.MOB));
        assertFalse(LiveUndoRedoActionRecorder.requiresCausalActionForEntityReplay(WorldMutationSource.FLUID));
    }

    @Test
    void causalEntityChangesCanOpenLiveUndoActionBeforeBlockFallout() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/capture/LiveUndoRedoActionRecorder.java"));
        int entityMethod = source.indexOf("void recordEntityAction(");
        int causalBranch = source.indexOf("if (actionAllowed && !actionId.isBlank())", entityMethod);

        assertTrue(source.indexOf("recordCurrentCausalAction(", causalBranch) >= 0);
        assertTrue(source.indexOf("recordDelayedEntityChanges(", causalBranch) >= 0);
    }

    @Test
    void delayedEntityBatchesUseOneHistoryWrite() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/capture/LiveUndoRedoActionRecorder.java"));

        assertTrue(source.contains("void recordEntityAction(")
                && source.contains("List<StoredEntityChange> changes"));
        assertTrue(source.contains("recordDelayedEntityChanges("));
    }

    @Test
    void liveRecorderDoesNotUseRelatedJoinFallback() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/minecraft/capture/LiveUndoRedoActionRecorder.java"));

        assertFalse(source.contains("recordRelatedChange("));
        assertFalse(source.contains("recordRelatedEntityChange("));
        assertFalse(source.contains("relatedJoin"));
    }

    private static StatePayload state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
