package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkingDraftLiveStateReconcilerTest {

    @Test
    void blockDraftChangesAreNotReconciledAgainstLiveWorld() {
        for (Method method : WorkingDraftLiveStateReconciler.class.getDeclaredMethods()) {
            assertTrue(!method.getName().contains("Block"));
        }
    }

    @Test
    void missingSpawnedEntityIsDroppedFromDraft() {
        Instant now = Instant.parse("2026-06-22T10:15:30Z");
        TrackedChangeBuffer buffer = buffer(now);
        String entityId = "00000000-0000-0000-0000-0000000000aa";
        buffer.addEntityChange(new StoredEntityChange(
                entityId,
                "minecraft:tnt",
                null,
                entity("minecraft:tnt", entityId)
        ), now);
        CaptureSessionState session = CaptureSessionState.create(buffer);

        boolean changed = new WorkingDraftLiveStateReconciler().reconcileEntities(
                session,
                List.of(new StoredEntityChange(entityId, "minecraft:tnt", null, null)),
                now.plusSeconds(1)
        );

        assertTrue(changed);
        assertTrue(buffer.isEmpty());
    }

    private static TrackedChangeBuffer buffer(Instant now) {
        return TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.AXIOM,
                now
        );
    }

    private static EntityPayload entity(String type, String uuid) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(1.0D));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
