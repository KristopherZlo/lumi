package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.CompareResultPayload;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientCompareStoreTest {
    private static final CommitId BEFORE =
            new CommitId(new ObjectId("1".repeat(ObjectId.HEX_LENGTH)));
    private static final CommitId AFTER =
            new CommitId(new ObjectId("2".repeat(ObjectId.HEX_LENGTH)));

    @Test
    void acceptsOnlyTheCurrentCorrelatedResult() {
        ClientCompareStore store = new ClientCompareStore();
        UUID stale = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        store.begin(stale, "minecraft:overworld", BEFORE, AFTER);
        store.begin(current, "minecraft:overworld", BEFORE, AFTER);

        store.accept(result(stale, "minecraft:overworld"));
        assertTrue(store.result().isEmpty());

        store.accept(result(current, "minecraft:the_nether"));
        assertTrue(store.result().isEmpty());

        store.accept(result(current, "minecraft:overworld"));
        assertEquals(current, store.result().orElseThrow().requestId());
    }

    @Test
    void clearDiscardsPendingAndPublishedState() {
        ClientCompareStore store = new ClientCompareStore();
        UUID request = UUID.randomUUID();
        store.begin(request, "minecraft:overworld", BEFORE, AFTER);
        store.accept(result(request, "minecraft:overworld"));

        store.clear();

        assertTrue(store.result().isEmpty());
        store.accept(result(request, "minecraft:overworld"));
        assertTrue(store.result().isEmpty());
    }

    @Test
    void retainsACompletedHighlightAndTogglesOnlyItsVisibility() {
        ClientCompareStore store = new ClientCompareStore();
        UUID request = UUID.randomUUID();
        store.begin(request, "minecraft:overworld", BEFORE, AFTER);
        store.accept(highlight(request));

        assertTrue(store.visibleResult().isPresent());
        assertEquals(false, store.toggleVisibility().orElseThrow());
        assertFalse(store.visibleResult().isPresent());
        assertEquals(true, store.toggleVisibility().orElseThrow());
        assertTrue(store.visibleResult().isPresent());
    }

    @Test
    void accumulatesOnlyOrderedCorrelatedBatches() {
        ClientCompareStore store = new ClientCompareStore();
        UUID request = UUID.randomUUID();
        store.begin(request, "minecraft:overworld", BEFORE, AFTER);
        store.accept(batch(request, 1, false, BlockChange.Kind.REMOVED));
        assertTrue(store.visibleChanges().isEmpty());

        store.accept(batch(request, 0, false, BlockChange.Kind.ADDED));
        store.accept(batch(request, 1, true, BlockChange.Kind.REMOVED));

        assertEquals(List.of(
                new BlockChange(0, 1, 2, BlockChange.Kind.ADDED),
                new BlockChange(1, 1, 2, BlockChange.Kind.REMOVED)),
                store.visibleChanges());
    }

    private static CompareResultPayload result(UUID request, String dimension) {
        return new CompareResultPayload(
                request, dimension, BEFORE, AFTER, 1, 0, List.of(), "");
    }

    private static CompareResultPayload highlight(UUID request) {
        return new CompareResultPayload(
                request, "minecraft:overworld", BEFORE, AFTER, 1, 0,
                List.of(new CompareResultPayload.ChangedSection(0, 4, 0)),
                List.of(), "");
    }

    private static CompareResultPayload batch(
            UUID request, int index, boolean complete, BlockChange.Kind kind) {
        return new CompareResultPayload(
                request, "minecraft:overworld", BEFORE, AFTER, 1, 0,
                List.of(), List.of(), "", index, complete,
                List.of(new BlockChange(index, 1, 2, kind)), index + 1L);
    }
}
