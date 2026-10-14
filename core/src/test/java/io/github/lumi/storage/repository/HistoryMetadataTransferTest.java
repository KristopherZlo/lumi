package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryMetadataTransferTest {
    @TempDir Path directory;

    @Test
    void transfersNeutralMetadataAndPreservesPointerScopeAndBuilderRevisions() throws Exception {
        Path source = directory.resolve("old"), target = directory.resolve("new");
        var workspace = new Workspace(new UUID(0, 1), "Build", Optional.empty(), WorkspaceSettings.defaults());
        new WorkspaceRepository(source).create(workspace);
        var zone = new Zone(new UUID(0, 2), workspace.id(), "Clock", 3,
                Set.of(new SectionKey(1, 2, 3)), Set.of(new UUID(0, 4)), 7);
        new ZoneRepository(source).create(zone);
        var active = new ActiveWorkspaceRepository(source);
        active.compareAndSet(active.create(new UUID(0, 5)), workspace.id());
        var branch = new ActiveBranchRepository(source);
        branch.compareAndSet(branch.create(new BranchName("main")), new BranchName("test"));
        var key = new SectionKey(1, 2, 3);
        new WorkingIndexRepository(source).write(new WorkingIndexRepository.State(
                new WorkingIndexSnapshot(Map.of(key, 5L)), new WorkingIndexSnapshot(Map.of(key, 3L))));
        var transfer = new HistoryMetadataTransfer();
        transfer.copy(source, target);
        transfer.copy(source, target);
        assertEquals(zone, new ZoneRepository(target).read(workspace.id(), zone.id()).orElseThrow());
        assertEquals(active.read(), new ActiveWorkspaceRepository(target).read());
        assertEquals(branch.read(), new ActiveBranchRepository(target).read());
        assertEquals(new WorkingIndexRepository(source).readState(), new WorkingIndexRepository(target).readState());
        var ref = new BranchRef(new BranchName("hidden/checkpoint"), new CommitId(ObjectId.hash(new byte[] { 1 })), 8);
        var refs = new BranchRefRepository(target);
        refs.initializeSnapshot(ref);
        refs.initializeSnapshot(ref);
        assertEquals(ref, refs.read(ref.name()).orElseThrow());
        assertThrows(RefConflictException.class, () -> refs.initializeSnapshot(new BranchRef(ref.name(), ref.commit(), 9)));
        Files.write(source.resolve("working/index.bin"), new byte[3]);
        assertThrows(IOException.class, () -> transfer.copy(source, directory.resolve("invalid")));
    }
}
