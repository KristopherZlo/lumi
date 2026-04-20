package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryRepositoryTest {

    @TempDir
    Path tempDir;

    private final RecoveryRepository repository = new RecoveryRepository();

    @Test
    void latestWalEntryWinsWhenLoadingDraft() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryDraft first = draft("minecraft:stone", Instant.parse("2026-04-20T10:00:00Z"));
        RecoveryDraft second = draft("minecraft:gold_block", Instant.parse("2026-04-20T10:05:00Z"));

        this.repository.saveDraft(layout, first);
        this.repository.saveDraft(layout, second);

        RecoveryDraft restored = this.repository.loadDraft(layout).orElseThrow();
        assertEquals(second.updatedAt(), restored.updatedAt());
        assertEquals("minecraft:gold_block", restored.changes().getFirst().newValue().blockId());
    }

    @Test
    void deleteDraftRemovesBaseAndWalFiles() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        this.repository.saveDraft(layout, draft("minecraft:stone", Instant.parse("2026-04-20T10:00:00Z")));

        this.repository.deleteDraft(layout);

        assertFalse(Files.exists(layout.recoveryBaseFile()));
        assertFalse(Files.exists(layout.recoveryWalFile()));
        assertTrue(this.repository.loadDraft(layout).isEmpty());
    }

    private static RecoveryDraft draft(String blockId, Instant updatedAt) {
        return new RecoveryDraft(
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                updatedAt.minusSeconds(30),
                updatedAt,
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone"),
                        payload(blockId)
                ))
        );
    }

    private static StatePayload payload(String blockId) {
        net.minecraft.nbt.CompoundTag state = new net.minecraft.nbt.CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }
}
