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

    @Test
    void operationDraftDoesNotReplaceLiveRecoveryDraft() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryDraft liveDraft = draft("minecraft:gold_block", Instant.parse("2026-04-20T10:00:00Z"));
        RecoveryDraft operationDraft = draft("minecraft:diamond_block", Instant.parse("2026-04-20T10:05:00Z"));

        this.repository.saveDraft(layout, liveDraft);
        this.repository.saveOperationDraft(layout, operationDraft);

        assertEquals("minecraft:gold_block", this.repository.loadDraft(layout).orElseThrow().changes().getFirst().newValue().blockId());
        assertEquals(
                "minecraft:diamond_block",
                this.repository.loadOperationDraft(layout).orElseThrow().changes().getFirst().newValue().blockId()
        );

        this.repository.deleteOperationDraft(layout);

        assertTrue(this.repository.loadOperationDraft(layout).isEmpty());
        assertTrue(this.repository.loadDraft(layout).isPresent());
    }

    @Test
    void serializesAirPayloadWhenRecoveryDraftContainsRemoval() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryDraft removalDraft = new RecoveryDraft(
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                Instant.parse("2026-04-20T10:00:00Z"),
                Instant.parse("2026-04-20T10:00:30Z"),
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone"),
                        StatePayload.air()
                ))
        );

        this.repository.saveDraft(layout, removalDraft);

        RecoveryDraft restored = this.repository.loadDraft(layout).orElseThrow();
        assertEquals("minecraft:air", restored.changes().getFirst().newValue().blockId());
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
