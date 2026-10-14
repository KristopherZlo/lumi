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
import io.github.lumi.domain.model.PlayerSpawn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    @Test
    void convertedIdentityRoundTripsWithoutChangingLegacyCommitBytes() throws IOException {
        var legacy = new Commit(ObjectId.hash(new byte[] { 1 }), List.of(),
                new CommitAuthor(new UUID(0, 1), "Builder"), "Original", Instant.EPOCH,
                new UUID(0, 2), Optional.empty(), CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0));
        byte[] original = codec.encode(legacy);
        assertEquals("92a6cff32a813413eb26df1b46e019f7a4a2015bea52000e525486db1a15c2cd", ObjectId.hash(original).hex());
        var converted = new Commit(legacy.tree(), legacy.parents(), legacy.author(), legacy.message(), legacy.timestamp(),
                legacy.workspaceId(), legacy.zoneId(), legacy.kind(), legacy.statistics(), legacy.playerSpawns(),
                Optional.of(CommitId.hash(original)));
        byte[] payload = codec.encode(converted);
        assertEquals("LUM3", new String(payload, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(converted, codec.decode(payload));
        assertThrows(IOException.class, () -> codec.decode(Arrays.copyOf(payload, 12)));
        assertThrows(IOException.class, () -> codec.decode(Arrays.copyOf(payload, payload.length - Integer.BYTES)));
    }

    @Test
    void readsCommitPayloadWrittenBeforePlayerSpawnExtension() throws IOException {
        Commit modern = new Commit(
                ObjectId.hash("tree".getBytes(StandardCharsets.UTF_8)), List.of(),
                new CommitAuthor(UUID.randomUUID(), "Builder"), "Old", Instant.EPOCH,
                UUID.randomUUID(), Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(0, 0, 0, 0));
        byte[] encoded = codec.encode(modern);

        Commit decoded = codec.decode(Arrays.copyOf(encoded, encoded.length - Integer.BYTES));

        assertEquals(modern, decoded);
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
                new CommitStatistics(4, 1, 27, 2),
                Map.of(
                        UUID.fromString("40000000-0000-0000-0000-000000000004"),
                        new PlayerSpawn(12, 64, -9, 90.0F, 10.0F, true),
                        UUID.fromString("50000000-0000-0000-0000-000000000005"),
                        new PlayerSpawn(-2, 80, 33, -45.0F, 0.0F, false)));
    }

    private static CommitId commitId(String value) {
        return CommitId.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
