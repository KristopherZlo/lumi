package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PackageBrowserStateTest {
    @Test
    void exposesOnlyImportedBranchesAndKeepsPagingBounded() throws Exception {
        var regular = branch("workspace/1/main", true, 'a');
        var imported = branch("import/clock-deadbeef", false, 'b');
        var namespaced = branch("workspace/1/import/roof-cafebabe", false, 'c');
        var state = new PackageBrowserState(
                Optional.empty(), List.of(regular, imported, namespaced));

        state.refreshLocal();
        assertEquals(0, state.size());
        assertFalse(state.canOpenFolder());

        state.selectTab(true);
        assertEquals(2, state.size());
        assertEquals(imported, state.imported(0));
        assertEquals(namespaced, state.imported(1));
        assertTrue(state.hasNext(1));
        state.changePage(1);
        assertEquals(1, state.start(1));
        assertTrue(state.hasPrevious());
        assertFalse(state.hasNext(1));

        state.confirmDelete(namespaced);
        assertEquals(Optional.of(namespaced), state.pendingDelete());
        state.cancelDelete();
        assertEquals(Optional.empty(), state.pendingDelete());
    }

    private static HistorySnapshotPayload.Branch branch(
            String name, boolean active, char digit) {
        return new HistorySnapshotPayload.Branch(
                name, new CommitId(new ObjectId(String.valueOf(digit).repeat(64))),
                active);
    }
}
