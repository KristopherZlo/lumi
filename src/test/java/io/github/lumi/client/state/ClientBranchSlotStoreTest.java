package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientBranchSlotStoreTest {
    @TempDir Path directory;

    @Test
    void assignsDefaultsThenPersistsExplicitConflictReplacement() {
        Path file = directory.resolve("slots");
        HistorySnapshotPayload snapshot = snapshot();
        ClientBranchSlotStore slots = new ClientBranchSlotStore(file);
        slots.synchronize(snapshot);

        assertEquals("main", slots.branch(snapshot, 0).orElseThrow().name());
        assertEquals("idea", slots.branch(snapshot, 1).orElseThrow().name());

        slots.assign(snapshot, "idea", 0);
        ClientBranchSlotStore reloaded = new ClientBranchSlotStore(file);
        assertEquals("idea", reloaded.branch(snapshot, 0).orElseThrow().name());
        assertTrue(reloaded.slot(snapshot, "main").isPresent() == false);
    }

    @Test
    void removesAssignmentsForBranchesThatNoLongerExist() {
        ClientBranchSlotStore slots =
                new ClientBranchSlotStore(directory.resolve("slots"));
        HistorySnapshotPayload snapshot = snapshot();
        slots.synchronize(snapshot);

        HistorySnapshotPayload withoutIdea = new HistorySnapshotPayload(
                snapshot.dimensionId(), snapshot.head(), snapshot.revision(),
                snapshot.pendingKeys(), snapshot.operationActive(),
                snapshot.recoveryPending(), snapshot.workspaceId(),
                snapshot.workspaceName(), snapshot.branchName(),
                snapshot.workspaces(), snapshot.versions(),
                List.of(snapshot.branches().getFirst()),
                snapshot.zones(), snapshot.deletedVersions());
        slots.synchronize(withoutIdea);

        assertTrue(slots.slot(withoutIdea, "idea").isPresent() == false);
    }

    private static HistorySnapshotPayload snapshot() {
        return new HistorySnapshotPayload(
                "minecraft:overworld", id('1'), 0, 0, false, false,
                new UUID(0, 1), "Build", "main", List.of(), List.of(),
                List.of(
                        new HistorySnapshotPayload.Branch("main", id('1'), true),
                        new HistorySnapshotPayload.Branch("idea", id('1'), false)),
                List.of(), List.of());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
