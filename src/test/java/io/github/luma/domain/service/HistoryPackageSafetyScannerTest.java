package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryPackageSafetyScannerTest {

    private final HistoryPackageSafetyScanner scanner = new HistoryPackageSafetyScanner();

    @Test
    void flagsExecutableBlockEntityPayloads() {
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:command_block");
        StatePayload commandBlock = new StatePayload(stateTag("minecraft:command_block"), blockEntity);

        var report = this.scanner.scanChanges(List.of(new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                StatePayload.air(),
                commandBlock
        )), List.of());

        assertFalse(report.safe());
        assertTrue(report.dangerousBlockEntityTypes().contains("minecraft:command_block"));
    }

    @Test
    void flagsCommandBlockMinecartPayloads() {
        String entityId = "00000000-0000-0000-0000-000000000001";

        var report = this.scanner.scanChanges(List.of(), List.of(new StoredEntityChange(
                entityId,
                "minecraft:command_block_minecart",
                null,
                entityTag(entityId, "minecraft:command_block_minecart")
        )));

        assertFalse(report.safe());
        assertTrue(report.dangerousEntityTypes().contains("minecraft:command_block_minecart"));
    }

    @Test
    void leavesNormalPayloadsSafe() {
        var report = this.scanner.scanChanges(List.of(new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                StatePayload.air(),
                new StatePayload(stateTag("minecraft:oak_planks"), null)
        )), List.of());

        assertTrue(report.safe());
    }

    @Test
    void failsClosedWhenRegistryLookupFails() {
        HistoryPackageSafetyScanner scanner = new HistoryPackageSafetyScanner(
                new PatchMetaRepository(),
                new PatchDataRepository(),
                new SnapshotReader(),
                new ThrowingHistoryPayloadTypeRegistry()
        );

        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:future_block_entity");
        String entityId = "00000000-0000-0000-0000-000000000002";

        var report = scanner.scanChanges(List.of(new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                StatePayload.air(),
                new StatePayload(stateTag("minecraft:oak_planks"), blockEntity)
        )), List.of(new StoredEntityChange(
                entityId,
                "minecraft:future_entity",
                null,
                entityTag(entityId, "minecraft:future_entity")
        )));

        assertFalse(report.safe());
        assertTrue(report.dangerousBlockEntityTypes().contains("minecraft:future_block_entity"));
        assertTrue(report.dangerousEntityTypes().contains("minecraft:future_entity"));
    }

    private static CompoundTag stateTag(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static EntityPayload entityTag(String entityId, String entityType) {
        CompoundTag tag = new CompoundTag();
        tag.putString("UUID", entityId);
        tag.putString("id", entityType);
        return new EntityPayload(tag);
    }

    private static final class ThrowingHistoryPayloadTypeRegistry implements HistoryPayloadTypeRegistry {

        @Override
        public boolean knownBlockEntityId(String id) {
            throw new IllegalStateException("registry unavailable");
        }

        @Override
        public boolean knownEntityId(String id) {
            throw new LinkageError("registry unavailable");
        }
    }
}
