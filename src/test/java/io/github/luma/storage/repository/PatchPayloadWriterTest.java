package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchPayloadWriterTest {

    @TempDir
    Path tempDir;

    private final PatchPayloadWriter writer = new PatchPayloadWriter();
    private final PatchDataRepository repository = new PatchDataRepository();

    @Test
    void writesCurrentChunkAddressablePayloadWithVisibleMetadata() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        List<StoredBlockChange> changes = List.of(
                change(1, 64, "minecraft:stone", "minecraft:gold_block", false),
                change(17, 80, "minecraft:dirt", "minecraft:diamond_block", true)
        );
        List<Integer> progress = new ArrayList<>();

        PatchMetadata metadata = this.writer.writePayload(
                layout, "patch-writer", "project", "version", changes, List.of(), progress::add
        );

        assertEquals(2, metadata.stats().changedBlocks());
        assertEquals(2, metadata.chunks().size());
        assertTrue(metadata.chunks().stream().allMatch(slice -> slice.dataOffsetBytes() >= 12L));
        assertEquals(1, metadata.chunks().stream().mapToInt(slice -> slice.visibleChangeCount()).sum());
        assertEquals(List.of(1, 2), progress);
        assertEquals(changes, this.repository.loadChanges(layout, metadata));
    }

    private static StoredBlockChange change(int x, int y, String oldBlock, String newBlock, boolean hidden) {
        return new StoredBlockChange(
                new BlockPoint(x, y, 1),
                payload(oldBlock),
                payload(newBlock),
                hidden
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
