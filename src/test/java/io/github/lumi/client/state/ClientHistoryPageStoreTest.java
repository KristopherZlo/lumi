package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.network.HistoryPagePayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientHistoryPageStoreTest {
    private static final UUID WORKSPACE = new UUID(0, 1);
    private static final BranchName MAIN = new BranchName("main");

    @Test
    void acceptsOnlyTheLatestCorrelatedPageForOneScope() {
        ClientHistoryPageStore store = new ClientHistoryPageStore();
        UUID stale = new UUID(0, 2);
        UUID latest = new UUID(0, 3);
        store.begin(stale, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.empty(), 0);
        store.begin(latest, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.empty(), 12);

        assertFalse(store.accept(page(stale, 0)));
        assertTrue(store.accept(page(latest, 12)));
        assertTrue(store.page(
                "minecraft:overworld", WORKSPACE, MAIN, Optional.empty())
                .filter(page -> page.offset() == 12)
                .isPresent());
    }

    @Test
    void keepsTheRenderedPageUntilItsReplacementArrives() {
        ClientHistoryPageStore store = new ClientHistoryPageStore();
        UUID current = new UUID(0, 12);
        UUID replacement = new UUID(0, 13);
        store.begin(current, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.empty(), 0);
        assertTrue(store.accept(page(current, 0)));

        store.begin(replacement, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.empty(), 0);

        assertTrue(store.page(
                "minecraft:overworld", WORKSPACE, MAIN, Optional.empty())
                .filter(page -> page.requestId().equals(current))
                .isPresent());
    }

    @Test
    void keepsZoneAndWorkspaceScopesIndependent() {
        ClientHistoryPageStore store = new ClientHistoryPageStore();
        UUID workspaceRequest = new UUID(0, 4);
        UUID zoneRequest = new UUID(0, 5);
        UUID zone = new UUID(0, 6);
        store.begin(workspaceRequest, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.empty(), 0);
        store.begin(zoneRequest, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.of(zone), 0);

        assertTrue(store.accept(page(workspaceRequest, 0)));
        assertTrue(store.accept(new HistoryPagePayload(
                zoneRequest, "minecraft:overworld", WORKSPACE, MAIN,
                Optional.of(zone), 0, false, List.of(), "")));
    }

    @Test
    void keepsTwoConsumersOnTheSameBranchIndependent() {
        ClientHistoryPageStore store = new ClientHistoryPageStore();
        var left = new ClientHistoryPageStore.Channel(new UUID(0, 7));
        var right = new ClientHistoryPageStore.Channel(new UUID(0, 8));
        UUID leftRequest = new UUID(0, 9);
        UUID rightRequest = new UUID(0, 10);
        store.begin(left, leftRequest, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.empty(), 0);
        store.begin(right, rightRequest, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.empty(), 12);

        assertTrue(store.accept(page(leftRequest, 0)));
        assertTrue(store.accept(page(rightRequest, 12)));
        assertTrue(store.page(
                left, "minecraft:overworld", WORKSPACE, MAIN, Optional.empty())
                .filter(page -> page.offset() == 0)
                .isPresent());
        assertTrue(store.page(
                right, "minecraft:overworld", WORKSPACE, MAIN, Optional.empty())
                .filter(page -> page.offset() == 12)
                .isPresent());
    }

    @Test
    void invalidatesEveryScopeInOneDimensionAndAdvancesItsRevision() {
        ClientHistoryPageStore store = new ClientHistoryPageStore();
        UUID request = new UUID(0, 11);
        store.begin(request, "minecraft:overworld", WORKSPACE,
                MAIN, Optional.empty(), 0);
        assertTrue(store.accept(page(request, 0)));

        store.invalidateDimension("minecraft:overworld");

        assertTrue(store.page(
                "minecraft:overworld", WORKSPACE, MAIN, Optional.empty())
                .isEmpty());
        assertTrue(store.revision("minecraft:overworld") == 1);
        assertTrue(store.revision("minecraft:the_nether") == 0);
    }

    private static HistoryPagePayload page(UUID request, int offset) {
        return new HistoryPagePayload(
                request, "minecraft:overworld", WORKSPACE, MAIN,
                Optional.empty(), offset, false, List.of(), "");
    }
}
