package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingRestorePublicationTest {
    @TempDir Path repositoryRoot;

    @Test
    void marksAppliedSectionsAsPendingWithoutMovingARef() throws Exception {
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), queued());
        SectionKey key = new SectionKey(1, 2, 3);
        var ref = new BranchRef(new BranchName("main"), id('1'), 4);
        var restore = new PreparedRestore(
                ref, id('2'), Map.of(key, section("minecraft:air")), Map.of(),
                Map.of(key, section("minecraft:stone")), Map.of());

        PendingRestorePublication publication = new PendingRestorePublication(mutations);
        publication.publish(restore);

        assertEquals(1L, mutations.snapshot().generations().get(key));
        assertFalse(publication.isDurable());
    }

    private static Executor queued() {
        return ignored -> { };
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static SectionBlob section(String state) {
        return new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, state)), Map.of());
    }
}
