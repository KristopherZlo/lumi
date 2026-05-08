package io.github.luma.minecraft.world;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import java.time.Instant;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotBatchPreparerTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void snapshotSectionsDecodePalettesOnceAndReuseCachedAirForMissingSections() throws Exception {
        CountingBlockStateDecoder decoder = new CountingBlockStateDecoder();
        SnapshotBatchPreparer preparer = new SnapshotBatchPreparer(decoder);
        short[] indexes = new short[SectionChangeMask.ENTRY_COUNT];
        for (int index = 0; index < indexes.length; index++) {
            indexes[index] = (short) (index & 1);
        }
        SnapshotSectionData section = new SnapshotSectionData(
                0,
                List.of(stateTag("minecraft:stone"), stateTag("minecraft:stone")),
                indexes
        );
        SnapshotData snapshot = new SnapshotData(
                "project",
                Instant.EPOCH,
                0,
                31,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(section),
                        null
                ))
        );

        List<PreparedChunkBatch> batches = preparer.prepare(snapshot, null);

        assertEquals(1, decoder.callsFor("minecraft:air"));
        assertEquals(1, decoder.callsFor("minecraft:stone"));
        assertEquals(1, batches.size());
        assertEquals(2, batches.getFirst().nativeSections().size());
        assertEquals(SectionApplyPath.SECTION_REWRITE, batches.getFirst().nativeSections().getFirst().safetyProfile().path());
    }

    @Test
    void snapshotEntityRestoreReplacesPlacedEntitiesForChunk() throws Exception {
        CountingBlockStateDecoder decoder = new CountingBlockStateDecoder();
        SnapshotBatchPreparer preparer = new SnapshotBatchPreparer(decoder);
        CompoundTag armorStand = entityTag("minecraft:armor_stand", "00000000-0000-0000-0000-000000000080");
        SnapshotData snapshot = new SnapshotData(
                "project",
                Instant.EPOCH,
                0,
                15,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(),
                        null,
                        List.of(new EntityPayload(armorStand))
                ))
        );

        List<PreparedChunkBatch> batches = preparer.prepare(snapshot, null);

        assertEquals(1, batches.size());
        assertEquals(true, batches.getFirst().entityBatch().replacePlacedEntities());
        assertEquals(1, batches.getFirst().entityBatch().entitiesToUpdate().size());
        assertEquals("minecraft:armor_stand", batches.getFirst()
                .entityBatch()
                .entitiesToUpdate()
                .getFirst()
                .getString("id")
                .orElse(""));
    }

    private static CompoundTag stateTag(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    private static CompoundTag entityTag(String type, String uuid) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(2.0D));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(2.0D));
        tag.put("Pos", pos);
        return tag;
    }
}
