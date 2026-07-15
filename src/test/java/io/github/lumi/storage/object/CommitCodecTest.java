package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommitCodecTest {
    private final CommitCodec codec = new CommitCodec();

    @Test
    void roundTripsCompleteCommitMetadata() throws IOException {
        Commit commit = commit(List.of(commitId("parent-1"), commitId("parent-2")));

        byte[] encoded = codec.encode(commit);

        assertEquals(commit, codec.decode(encoded));
        assertArrayEquals(encoded, codec.encode(codec.decode(encoded)));
    }

    @Test
    void limitsHistoryToOrdinaryAndMergeParents() {
        assertThrows(IllegalArgumentException.class,
                () -> commit(List.of(commitId("one"), commitId("two"), commitId("three"))));
    }

    private static Commit commit(List<CommitId> parents) {
        return new Commit(
                ObjectId.hash("tree".getBytes(StandardCharsets.UTF_8)),
                parents,
                new CommitAuthor(UUID.fromString("10000000-0000-0000-0000-000000000001"), "Builder"),
                "Try the tower",
                Instant.parse("2026-07-15T12:34:56.123456789Z"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Optional.of(UUID.fromString("30000000-0000-0000-0000-000000000003")),
                CommitKind.MERGE,
                new CommitStatistics(4, 1, 27, 2));
    }

    private static CommitId commitId(String value) {
        return CommitId.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
