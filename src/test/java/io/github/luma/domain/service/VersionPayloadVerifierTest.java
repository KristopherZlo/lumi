package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionPayloadVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void rereadsMatchingPatchBeforePublication() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryDraft draft = draft();
        var metadata = new PatchDataRepository().writePayload(
                layout, "p0001", "project", "v0001", draft.changes(), draft.entityChanges()
        );
        new PatchMetaRepository().save(layout, metadata);

        assertDoesNotThrow(() -> new VersionPayloadVerifier().verify(layout, metadata, draft, "", ""));
    }

    @Test
    void rejectsCorruptPatchBeforePublication() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryDraft draft = draft();
        var metadata = new PatchDataRepository().writePayload(
                layout, "p0001", "project", "v0001", draft.changes(), draft.entityChanges()
        );
        new PatchMetaRepository().save(layout, metadata);
        Files.write(layout.patchDataFile(metadata.id()), new byte[] {1, 2, 3});

        assertThrows(IOException.class, () -> new VersionPayloadVerifier().verify(layout, metadata, draft, "", ""));
    }

    @Test
    void rejectsMetadataThatDoesNotRoundTrip() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryDraft draft = draft();
        var metadata = new PatchDataRepository().writePayload(
                layout, "p0001", "project", "v0001", draft.changes(), draft.entityChanges()
        );
        PatchMetadata corruptedMetadata = new PatchMetadata(
                metadata.id(), metadata.projectId(), metadata.versionId(), metadata.dataFileName(),
                List.of(), metadata.stats(), metadata.entityChunkIndex()
        );
        new PatchMetaRepository().save(layout, corruptedMetadata);

        assertThrows(IOException.class, () -> new VersionPayloadVerifier().verify(
                layout, metadata, draft, "", ""
        ));
    }

    private static RecoveryDraft draft() {
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new RecoveryDraft(
                "project", "main", "root", "tester", WorldMutationSource.PLAYER, now, now,
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 64, 1), StatePayload.air(), new StatePayload(state, null)
                ))
        );
    }
}
