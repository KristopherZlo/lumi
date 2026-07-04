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
    void primedTntEntityReplayLogsLoadRestoreAndSpawnPhases() throws IOException {
        String applier = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/world/BlockChangeApplier.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(applier.contains("PRIMED_TNT_ENTITY_TYPE = \"minecraft:tnt\""));
        assertTrue(applier.contains("logEntityReplay(\"load\""));
        assertTrue(applier.contains("logEntityReplay(\"restore-existing\""));
        assertTrue(applier.contains("logEntityReplay(\"spawn\""));
        assertTrue(applier.contains("LumaLoadLog.event(\"tnt-replay\", \"entity-replay\""));
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
