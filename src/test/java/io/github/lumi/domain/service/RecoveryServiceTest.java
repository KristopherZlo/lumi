package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void preparesResumeTargetFromReturnCheckpoint() throws Exception {
        Fixture fixture = fixture();

        PreparedRestore restore = fixture.service.prepare(
                fixture.journal, RecoveryChoice.RESUME_TARGET);

        assertEquals(fixture.target, restore.targetCommit());
        assertEquals(Map.of(fixture.player, fixture.after), restore.playerSpawns());
        assertEquals(Map.of(fixture.player, fixture.before), restore.returnPlayerSpawns());
    }

    @Test
    void preparesReturnCheckpointAsTheVerifiedTarget() throws Exception {
        Fixture fixture = fixture();

        PreparedRestore restore = fixture.service.prepare(
                fixture.journal, RecoveryChoice.RETURN_CHECKPOINT);

        assertEquals(fixture.checkpoint, restore.targetCommit());
        assertEquals(Map.of(fixture.player, fixture.before), restore.playerSpawns());
        assertEquals(Map.of(fixture.player, fixture.after), restore.returnPlayerSpawns());
    }

    private Fixture fixture() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        UUID player = new UUID(0, 4);
        PlayerSpawn before = new PlayerSpawn(1, 64, 1, 0, 0, false);
        PlayerSpawn after = new PlayerSpawn(8, 70, -2, 90, 0, true);
        CommitId checkpoint = commits.write(commit(tree, Map.of(player, before)));
        CommitId target = commits.write(commit(tree, Map.of(player, after)));
        var journal = new OperationJournal(
                UUID.randomUUID(), OperationKind.RESTORE, OperationPhase.APPLYING,
                new OperationTarget(new BranchName("main"), checkpoint, 3,
                        Optional.of(target), Optional.of(checkpoint)));
        var restores = new RestoreService(objects, commits, new OriginStore(repositoryRoot));
        return new Fixture(
                new RecoveryService(restores), journal, checkpoint, target,
                player, before, after);
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree, Map<UUID, PlayerSpawn> spawns) {
        return new Commit(tree, List.of(), new CommitAuthor(new UUID(0, 1), "Builder"),
                "Recovery", Instant.EPOCH, new UUID(0, 2), Optional.empty(),
                CommitKind.HIDDEN_RETURN, new CommitStatistics(0, 0, 0, 0), spawns);
    }

    private record Fixture(
            RecoveryService service,
            OperationJournal journal,
            CommitId checkpoint,
            CommitId target,
            UUID player,
            PlayerSpawn before,
            PlayerSpawn after) { }
}
