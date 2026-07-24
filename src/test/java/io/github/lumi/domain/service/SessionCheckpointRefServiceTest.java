package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.RefConflictException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCheckpointRefServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void releasesOnlyTheExactSessionRefAndIsIdempotent() throws Exception {
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        SessionCheckpointRefService service = new SessionCheckpointRefService(refs);
        BranchRef checkpoint = refs.create(service.name(new UUID(0, 1)), commit('1'));
        BranchRef stale = refs.create(service.name(new UUID(0, 2)), commit('1'));
        BranchRef changed = refs.compareAndSet(stale, commit('2'));
        BranchRef visible = refs.create(new BranchName("main"), commit('2'));

        assertTrue(service.release(checkpoint));
        assertTrue(!service.release(checkpoint));
        assertThrows(RefConflictException.class, () -> service.release(stale));
        assertEquals(changed, refs.read(changed.name()).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> service.release(visible));
        assertEquals(visible, refs.read(visible.name()).orElseThrow());
    }

    @Test
    void prunesRestartOrphansButKeepsRecoveryAndVisibleRefs() throws Exception {
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        SessionCheckpointRefService service = new SessionCheckpointRefService(refs);
        CommitId retained = commit('1');
        BranchRef keep = refs.create(service.name(new UUID(0, 1)), retained);
        BranchRef orphan = refs.create(service.name(new UUID(0, 2)), commit('2'));
        BranchRef visible = refs.create(new BranchName("main"), commit('3'));

        assertEquals(1, service.pruneOrphans(Set.of(retained)));
        assertEquals(keep, refs.read(keep.name()).orElseThrow());
        assertTrue(refs.read(orphan.name()).isEmpty());
        assertEquals(visible, refs.read(visible.name()).orElseThrow());
    }

    private static CommitId commit(char value) {
        return new CommitId(new ObjectId(String.valueOf(value).repeat(64)));
    }
}
