package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
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

    @Test
    void roundTripsEntityChangesInChunkPayload() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        String entityId = "00000000-0000-0000-0000-000000000030";
        List<StoredEntityChange> entityChanges = List.of(new StoredEntityChange(
                entityId,
                "minecraft:block_display",
                entity("minecraft:block_display", entityId, 1.0D),
                entity("minecraft:block_display", entityId, 2.0D)
        ));

        PatchMetadata metadata = this.repository.writePayload(
                layout,
                "patch-entity",
                "project",
                "v0002",
                List.of(),
                entityChanges
        );
        PatchWorldChanges restored = this.repository.loadWorldChanges(layout, metadata);

        assertTrue(restored.blockChanges().isEmpty());
        assertEquals(1, restored.entityChanges().size());
        assertEquals(entityId, restored.entityChanges().getFirst().entityId());
        assertEquals(2.0D, restored.entityChanges().getFirst().newValue()
                .entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
    }

    private static StatePayload payload(String blockId, net.minecraft.nbt.CompoundTag blockEntity) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, blockEntity);
    }

    private static CompoundTag blockEntity(String id, int items) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("Items", items);
        return tag;
    }

    private static EntityPayload entity(String type, String uuid, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
