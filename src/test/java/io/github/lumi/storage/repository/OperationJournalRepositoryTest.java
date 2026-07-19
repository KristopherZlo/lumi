package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchSwitchTarget;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.WorkspaceSwitchTarget;
import io.github.lumi.domain.model.ZoneRestoreTarget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationJournalRepositoryTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void persistsImmutableTargetsWhilePhaseAdvances() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationJournal created = repository.create(new OperationJournal(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                OperationKind.RESTORE,
                OperationPhase.PREPARED,
                target()));

        OperationJournal applying = repository.advance(created, OperationPhase.APPLYING);

        assertEquals(created.target(), applying.target());
        assertEquals(applying, new OperationJournalRepository(repositoryRoot).read().orElseThrow());
    }

    @Test
    void preservesCapturedGenerationsWithoutRewritingThemOnPhaseAdvance() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(Map.of(
                new EntityChunkKey(-4, 8), 7L,
                new SectionKey(3, -2, 1), 5L));
        OperationJournal created = repository.create(new OperationJournal(
                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                OperationKind.RESTORE,
                OperationPhase.PREPARED,
                target(),
                Optional.of(boundary)));
        Path sidecar = repositoryRoot.resolve("operations/active-generations.bin");
        Files.setLastModifiedTime(sidecar, FileTime.from(Instant.parse("2000-01-01T00:00:00Z")));
        FileTime sidecarTimestamp = Files.getLastModifiedTime(sidecar);

        OperationJournal applying = repository.advance(created, OperationPhase.APPLYING);

        assertEquals(Optional.of(boundary), applying.capturedGenerations());
        assertEquals(applying, new OperationJournalRepository(repositoryRoot).read().orElseThrow());
        assertEquals(sidecarTimestamp, Files.getLastModifiedTime(sidecar));
        repository.clear(applying);
        assertFalse(Files.exists(repositoryRoot.resolve("operations/active.bin")));
        assertFalse(Files.exists(sidecar));
    }

    @Test
    void readsLegacyJournalWithoutGenerationBoundaryFlagAsAbsent() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationJournal created = repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.SAVE, OperationPhase.PREPARED, target()));
        Path journal = repositoryRoot.resolve("operations/active.bin");
        byte[] current = Files.readAllBytes(journal);
        Files.write(journal, Arrays.copyOf(current, current.length - 1));

        OperationJournal decoded = new OperationJournalRepository(repositoryRoot)
                .read().orElseThrow();

        assertEquals(created, decoded);
        assertEquals(Optional.empty(), decoded.capturedGenerations());
    }

    @Test
    void rejectsGenerationBoundaryForAnotherOperation() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE, OperationPhase.PREPARED, target(),
                Optional.of(new WorkingIndexSnapshot(Map.of(new SectionKey(1, 2, 3), 4L)))));
        Path sidecar = repositoryRoot.resolve("operations/active-generations.bin");
        byte[] payload = Files.readAllBytes(sidecar);
        payload[4] ^= 1;
        Files.write(sidecar, payload);

        assertThrows(IOException.class,
                () -> new OperationJournalRepository(repositoryRoot).read());
    }

    @Test
    void stalePhaseCannotAdvanceOrClearJournal() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationJournal prepared = repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.SAVE, OperationPhase.PREPARED, target()));
        OperationJournal writing = repository.advance(prepared, OperationPhase.WRITING_OBJECTS);

        assertThrows(JournalConflictException.class,
                () -> repository.advance(prepared, OperationPhase.COMMIT_WRITTEN));
        assertThrows(JournalConflictException.class, () -> repository.clear(prepared));
        repository.clear(writing);
        assertEquals(Optional.empty(), repository.read());
    }

    @Test
    void persistsBranchSwitchRefAndPointerRevisions() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationTarget target = new OperationTarget(
                new BranchName("main"), id("expected"), 7,
                Optional.of(id("target")), Optional.of(id("return")),
                Optional.of(new BranchSwitchTarget(
                        new BranchName("redstone-test"), 3, 5)));

        var created = repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.BRANCH_SWITCH,
                OperationPhase.PREPARED, target));

        assertEquals(created, new OperationJournalRepository(repositoryRoot)
                .read().orElseThrow());
    }

    @Test
    void persistsWorkspaceSwitchPointerRevisions() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationTarget target = new OperationTarget(
                new BranchName("main"), id("expected"), 7,
                Optional.of(id("target")), Optional.of(id("return")),
                Optional.of(new BranchSwitchTarget(new BranchName("workspace/next/main"), 3, 5)),
                Optional.empty(), false,
                Optional.of(new WorkspaceSwitchTarget(
                        new UUID(0, 1), new UUID(0, 2), 4)));

        var created = repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.BRANCH_SWITCH,
                OperationPhase.PREPARED, target));

        assertEquals(created, new OperationJournalRepository(repositoryRoot)
                .read().orElseThrow());
    }

    @Test
    void persistsImmutableZoneRestoreRevision() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationTarget target = new OperationTarget(
                new BranchName("main"), id("expected"), 7,
                Optional.of(id("target")), Optional.of(id("return")),
                Optional.empty(), Optional.empty(), false, Optional.empty(),
                Optional.of(new ZoneRestoreTarget(new UUID(0, 1), new UUID(0, 2), 5)));

        var created = repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE,
                OperationPhase.PREPARED, target));

        assertEquals(created, new OperationJournalRepository(repositoryRoot)
                .read().orElseThrow());
    }

    @Test
    void persistsPartialRestoreArea() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        var area = new BlockAreaTarget(new BlockBox(-3, 4, 5, 7, 8, 9), true);
        OperationTarget target = new OperationTarget(
                new BranchName("main"), id("expected"), 7,
                Optional.of(id("target")), Optional.of(id("return")),
                Optional.empty(), Optional.of(area));

        var created = repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE,
                OperationPhase.PREPARED, target));

        assertEquals(created, new OperationJournalRepository(repositoryRoot)
                .read().orElseThrow());
    }

    @Test
    void persistsEntityExclusionForCrashRecovery() throws IOException {
        OperationJournalRepository repository = new OperationJournalRepository(repositoryRoot);
        OperationTarget target = new OperationTarget(
                new BranchName("main"), id("expected"), 7,
                Optional.of(id("target")), Optional.of(id("return")),
                Optional.empty(), Optional.empty(), true);

        var created = repository.create(new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE,
                OperationPhase.PREPARED, target));

        assertEquals(created, new OperationJournalRepository(repositoryRoot)
                .read().orElseThrow());
    }

    private static OperationTarget target() {
        return new OperationTarget(
                new BranchName("main"), id("expected"), 7,
                Optional.of(id("target")), Optional.of(id("return")));
    }

    private static CommitId id(String value) {
        return CommitId.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
