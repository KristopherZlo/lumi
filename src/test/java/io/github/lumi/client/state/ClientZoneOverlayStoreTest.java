package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.ZoneShellFace;
import io.github.lumi.network.ZoneOverlayPayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientZoneOverlayStoreTest {
    private static final UUID REQUEST = new UUID(0, 1);
    private static final UUID WORKSPACE = new UUID(0, 2);
    private static final UUID ZONE = new UUID(0, 3);

    @Test
    void publishesOnlyACompleteOrderedSnapshot() {
        ClientZoneOverlayStore store = new ClientZoneOverlayStore();
        store.begin(REQUEST, "minecraft:overworld", WORKSPACE);

        assertTrue(store.loading("minecraft:overworld", WORKSPACE));
        assertTrue(store.accept(payload(0, false, List.of(face(0)))));
        assertTrue(store.snapshot().isEmpty());
        assertTrue(store.accept(payload(1, true, List.of(face(16)))));

        var snapshot = store.snapshot().orElseThrow();
        assertEquals(2, snapshot.zones().getFirst().faces().size());
        assertEquals("", snapshot.error());
        assertFalse(store.loading("minecraft:overworld", WORKSPACE));
    }

    @Test
    void retainsTheReadyShellWhileItsReplacementLoads() {
        ClientZoneOverlayStore store = new ClientZoneOverlayStore();
        store.begin(REQUEST, "minecraft:overworld", WORKSPACE);
        assertTrue(store.accept(payload(0, true, List.of(face(0)))));
        var ready = store.snapshot().orElseThrow();

        UUID replacement = new UUID(0, 4);
        store.begin(replacement, "minecraft:overworld", WORKSPACE);

        assertEquals(ready, store.snapshot().orElseThrow());
        assertTrue(store.loading("minecraft:overworld", WORKSPACE));
        assertTrue(store.accept(payload(
                replacement, 0, true, List.of(face(16)))));
        assertEquals(
                16,
                store.snapshot().orElseThrow()
                        .zones().getFirst().faces().getFirst().plane());
    }

    @Test
    void clearsAReadyShellWhenTheRequestScopeChanges() {
        ClientZoneOverlayStore store = new ClientZoneOverlayStore();
        store.begin(REQUEST, "minecraft:overworld", WORKSPACE);
        assertTrue(store.accept(payload(0, true, List.of(face(0)))));

        UUID otherWorkspace = new UUID(0, 5);
        store.begin(
                new UUID(0, 6), "minecraft:the_nether", otherWorkspace);

        assertTrue(store.snapshot().isEmpty());
        assertFalse(store.loading("minecraft:overworld", WORKSPACE));
    }

    @Test
    void keepsTheReadyShellWhenARefreshFails() {
        ClientZoneOverlayStore store = new ClientZoneOverlayStore();
        store.begin(REQUEST, "minecraft:overworld", WORKSPACE);
        assertTrue(store.accept(payload(0, true, List.of(face(0)))));
        var ready = store.snapshot().orElseThrow();

        UUID replacement = new UUID(0, 7);
        store.begin(replacement, "minecraft:overworld", WORKSPACE);
        assertTrue(store.accept(new ZoneOverlayPayload(
                replacement, "minecraft:overworld", WORKSPACE,
                0, true, Optional.empty(), "failed")));

        assertEquals(ready, store.snapshot().orElseThrow());
        assertFalse(store.loading("minecraft:overworld", WORKSPACE));
    }

    @Test
    void rejectsStaleAndOutOfOrderBatches() {
        ClientZoneOverlayStore store = new ClientZoneOverlayStore();
        store.begin(REQUEST, "minecraft:overworld", WORKSPACE);

        assertFalse(store.accept(new ZoneOverlayPayload(
                new UUID(9, 9), "minecraft:overworld", WORKSPACE,
                0, true, Optional.empty(), "")));
        assertFalse(store.accept(payload(1, true, List.of())));
        assertTrue(store.snapshot().isEmpty());
    }

    private static ZoneOverlayPayload payload(
            int index, boolean complete, List<ZoneShellFace> faces) {
        return payload(REQUEST, index, complete, faces);
    }

    private static ZoneOverlayPayload payload(
            UUID request,
            int index,
            boolean complete,
            List<ZoneShellFace> faces) {
        return new ZoneOverlayPayload(
                request, "minecraft:overworld", WORKSPACE,
                index, complete,
                Optional.of(new ZoneOverlayPayload.ZoneBatch(
                        ZONE, "Gate", 0xff336699, 4, true, false, faces)),
                "");
    }

    private static ZoneShellFace face(int plane) {
        return new ZoneShellFace(
                ZoneShellFace.Side.UP, plane, 0, 16, 0, 16);
    }
}
