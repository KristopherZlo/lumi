package io.github.luma.minecraft.bootstrap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldChunkActivityClassifierTest {

    private final WorldChunkActivityClassifier classifier = new WorldChunkActivityClassifier();

    @Test
    void skipsChunksWithoutPersistedActivityMarkers() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("InhabitedTime", 0L);

        assertFalse(this.classifier.shouldBackup(tag));
    }

    @Test
    void keepsChunksWithPlayerActivityOrPersistentPayloads() {
        CompoundTag inhabited = new CompoundTag();
        inhabited.putLong("InhabitedTime", 1L);
        CompoundTag blockEntities = new CompoundTag();
        ListTag entries = new ListTag();
        entries.add(new CompoundTag());
        blockEntities.put("block_entities", entries);

        assertTrue(this.classifier.shouldBackup(inhabited));
        assertTrue(this.classifier.shouldBackup(blockEntities));
    }
}
