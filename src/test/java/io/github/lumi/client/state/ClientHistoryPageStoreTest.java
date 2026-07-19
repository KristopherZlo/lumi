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

    private static HistoryPagePayload page(UUID request, int offset) {
        return new HistoryPagePayload(
                request, "minecraft:overworld", WORKSPACE, MAIN,
                Optional.empty(), offset, false, List.of(), "");
    }
}
