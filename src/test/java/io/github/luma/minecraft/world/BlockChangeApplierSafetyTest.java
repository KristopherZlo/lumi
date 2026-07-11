package io.github.luma.minecraft.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockChangeApplierSafetyTest {

    @Test
    void creeperReplayResetClearsIgnitionAndSwellState() throws IOException {
        String applier = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/BlockChangeApplier.java"),
                StandardCharsets.UTF_8
        );
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"), StandardCharsets.UTF_8);
        String normalizedApplier = applier.replace("\r\n", "\n");

        assertTrue(mixins.contains("\"CreeperReplayStateAccess\""));
        assertTrue(normalizedApplier.contains("resetCreeperReplayState(entity);"));
        assertTrue(normalizedApplier.contains("existing.restoreFrom(entity);\n                    resetCreeperReplayState(existing);"));
        assertTrue(applier.contains("access.luma$setSwell(0);"));
        assertTrue(applier.contains("access.luma$setOldSwell(0);"));
        assertTrue(applier.contains("creeper.setSwellDir(-1);"));
        assertTrue(applier.contains("CreeperReplayStateAccess.luma$dataIsIgnited(), false"));
    }

    @Test
    void entityReplayDoesNotCreateLiveActionContext() throws IOException {
        String applier = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/BlockChangeApplier.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(applier.contains("ReplayContext"));
        assertFalse(applier.contains("rememberReplayAction"));
    }

    @Test
    void replayClearsQueuedTicksAndBlockEventsForRestoredPositions() throws IOException {
        String cleaner = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/ReplayQueuedTickCleaner.java"),
                StandardCharsets.UTF_8
        );
        String applier = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/BlockChangeApplier.java"),
                StandardCharsets.UTF_8
        );
        String directSection = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/DirectSectionBlockCommitStrategy.java"),
                StandardCharsets.UTF_8
        );
        String sectionNative = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/SectionNativeBlockCommitStrategy.java"),
                StandardCharsets.UTF_8
        );
        String directChunk = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/DirectChunkBlockCommitStrategy.java"),
                StandardCharsets.UTF_8
        );
        String exactPlacement = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/MinecraftExactReplayPlacementApplier.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(cleaner.contains("level.clearBlockEvents(box);"));
        assertTrue(cleaner.contains("level.getBlockTicks().clearArea(box);"));
        assertTrue(cleaner.contains("level.getFluidTicks().clearArea(box);"));
        assertTrue(applier.contains("ReplayQueuedTickCleaner.clear(level, pos);"));
        assertTrue(directSection.contains("ReplayQueuedTickCleaner.clear(level, pos);"));
        assertTrue(sectionNative.contains("ReplayQueuedTickCleaner.clear(level, mutablePos);"));
        assertTrue(directChunk.contains("ReplayQueuedTickCleaner.clear(level, mutablePos);"));
        assertTrue(exactPlacement.contains("ReplayQueuedTickCleaner.clear(level, pos);"));
    }

    @Test
    void blockEntityTailCannotRestoreAnEntityIntoAnIncompatibleBlock() throws IOException {
        String applier = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/BlockChangeApplier.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(applier.contains("BLOCK_STATE_POLICY.normalize(state,"));
        assertTrue(applier.contains("if (persistentState.blockEntityTag() == null)"));
        assertTrue(applier.contains("BLOCK_ENTITY_REMOVER.remove(level, pos);"));
    }

    @Test
    void removalDeletesLoadedAndPendingBlockEntities() throws IOException {
        String remover = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/BlockEntityRemover.java"),
                StandardCharsets.UTF_8
        ).replace("\r\n", "\n");

        assertTrue(remover.contains("level.removeBlockEntity(pos);\n"
                + "        level.getBlockEntity(pos);\n"
                + "        level.removeBlockEntity(pos);"));
    }

    @Test
    void pendingBlockEntityNbtRequiresACompatibleLiveState() throws IOException {
        String mixin = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/ChunkAccessBlockEntityNbtMixin.java"),
                StandardCharsets.UTF_8
        );
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"), StandardCharsets.UTF_8);

        assertTrue(mixins.contains("\"ChunkAccessBlockEntityNbtMixin\""));
        assertTrue(mixin.contains("if (!chunk.getBlockState(pos).hasBlockEntity())"));
        assertTrue(mixin.contains("callback.cancel();"));
    }

    @Test
    void blockEntityTailExceptionCountsAsProcessedAndRecordsFailure() {
        WorldApplyMetrics metrics = new WorldApplyMetrics();
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");

        int processed = BlockChangeApplier.applyBlockEntities(
                null,
                List.of(Map.entry(new BlockPos(1, 64, 1), blockEntity)),
                0,
                1,
                metrics
        );

        assertEquals(1, processed);
        assertTrue(metrics.summary().contains("applyFailures=1"));
    }
}
