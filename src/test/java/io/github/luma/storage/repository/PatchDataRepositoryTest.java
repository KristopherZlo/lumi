package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchDataRepositoryTest {

    @TempDir
    Path tempDir;

    private final PatchDataRepository repository = new PatchDataRepository();

    @Test
    void roundTripsChunkIndexedPayloadWithBlockEntities() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone", null),
                        payload("minecraft:chest", blockEntity("minecraft:chest", 1))
                ),
                new StoredBlockChange(
                        new BlockPoint(17, 65, 2),
                        payload("minecraft:dirt", null),
                        payload("minecraft:diamond_block", null)
                )
        );

        PatchMetadata metadata = this.repository.writePayload(layout, "patch-0001", "project", "v0001", changes);
        List<StoredBlockChange> restored = this.repository.loadChanges(layout, metadata);

        assertEquals(2, metadata.stats().changedBlocks());
        assertEquals(2, metadata.stats().changedChunks());
        assertEquals(changes, restored);
        assertTrue(metadata.chunks().stream().anyMatch(slice -> slice.chunkX() == 0 && slice.chunkZ() == 0));
        assertTrue(metadata.chunks().stream().anyMatch(slice -> slice.chunkX() == 1 && slice.chunkZ() == 0));
    }

    private static StatePayload payload(String blockId, net.minecraft.nbt.CompoundTag blockEntity) {
        net.minecraft.nbt.CompoundTag state = new net.minecraft.nbt.CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, blockEntity);
    }

    private static net.minecraft.nbt.CompoundTag blockEntity(String id, int items) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", id);
        tag.putInt("Items", items);
        return tag;
    }
}
