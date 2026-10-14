package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionDisplayName;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.VersionDisplayNameRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionDisplayNameServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void renamesOnlyVisibleVersionsInTheActiveWorkspace() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        VersionDisplayNameService service = new VersionDisplayNameService(
                commits, new VersionDisplayNameRepository(repositoryRoot));
        UUID activeWorkspace = new UUID(0, 2);
        CommitId visible = commits.write(
                commit(activeWorkspace, CommitKind.MANUAL, "Original"));

        assertEquals("Original", service.read(visible, "Original"));
        service.replace(visible, activeWorkspace,
                new VersionDisplayName("Renamed"));

        assertEquals("Renamed", service.read(visible, "Original"));
    }

    @Test
    void refusesForeignAndInternalVersions() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        VersionDisplayNameService service = new VersionDisplayNameService(
                commits, new VersionDisplayNameRepository(repositoryRoot));
        UUID activeWorkspace = new UUID(0, 2);
        CommitId foreign = commits.write(commit(
                new UUID(0, 3), CommitKind.MANUAL, "Foreign"));

        assertThrows(IOException.class, () -> service.replace(
                foreign, activeWorkspace, new VersionDisplayName("Renamed")));
        for (CommitKind kind : List.of(
                CommitKind.HIDDEN_SAFETY, CommitKind.HIDDEN_RETURN)) {
            CommitId hidden = commits.write(
                    commit(activeWorkspace, kind, kind.name()));
            assertThrows(IllegalArgumentException.class, () -> service.replace(
                    hidden, activeWorkspace, new VersionDisplayName("Renamed")));
        }
    }

    private static Commit commit(
            UUID workspace, CommitKind kind, String message) {
        return new Commit(
                ObjectId.hash(new byte[] {1}), List.of(),
                new CommitAuthor(new UUID(0, 1), "Builder"),
                message, Instant.EPOCH, workspace, Optional.empty(), kind,
                new CommitStatistics(0, 0, 0, 0));
    }
}
