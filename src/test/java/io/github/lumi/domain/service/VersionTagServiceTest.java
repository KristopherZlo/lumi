package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.VersionTagRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionTagServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void replacesTagsOnlyForVisibleVersionsInTheActiveWorkspace() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        VersionTagRepository tags = new VersionTagRepository(repositoryRoot);
        VersionTagService service = new VersionTagService(commits, tags);
        UUID activeWorkspace = new UUID(0, 2);
        CommitId visible = commits.write(
                commit(activeWorkspace, CommitKind.MANUAL, "Visible"));

        service.replace(visible, activeWorkspace, VersionTags.parse("roof, castle"));

        assertEquals(VersionTags.parse("roof, castle"), service.read(visible));
    }

    @Test
    void refusesForeignAndInternalVersionsWithoutReplacingTheirTags() throws Exception {
        CommitRepository commits = new CommitRepository(repositoryRoot);
        VersionTagRepository tags = new VersionTagRepository(repositoryRoot);
        VersionTagService service = new VersionTagService(commits, tags);
        UUID activeWorkspace = new UUID(0, 2);
        VersionTags original = VersionTags.parse("keep");
        CommitId foreign = commits.write(
                commit(new UUID(0, 3), CommitKind.MANUAL, "Foreign"));
        tags.replace(foreign, original);

        assertThrows(IOException.class, () -> service.replace(
                foreign, activeWorkspace, VersionTags.parse("replace")));
        assertEquals(original, tags.read(foreign));

        for (CommitKind kind : List.of(
                CommitKind.HIDDEN_SAFETY, CommitKind.HIDDEN_RETURN)) {
            CommitId hidden = commits.write(commit(activeWorkspace, kind, kind.name()));
            tags.replace(hidden, original);
            assertThrows(IllegalArgumentException.class, () -> service.replace(
                    hidden, activeWorkspace, VersionTags.parse("replace")));
            assertEquals(original, tags.read(hidden));
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
