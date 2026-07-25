package io.github.lumi.client.preview;

import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/** Public client lifecycle facade for commit-correlated isometric previews. */
public final class ClientVersionPreviewCapture {
    private final IsometricPreviewCoordinator coordinator;

    public ClientVersionPreviewCapture(ClientVersionPreviewStore store) {
        coordinator = new IsometricPreviewCoordinator(
                Objects.requireNonNull(store, "store"));
    }

    public void register() {
        WorldRenderEvents.END_MAIN.register(context -> coordinator.tick());
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(coordinator::clear));
    }

    public void request(UUID requestId, HistorySnapshotPayload snapshot) {
        coordinator.request(requestId, snapshot);
    }

    public void accept(OperationEventPayload event) {
        coordinator.accept(event);
    }

    public void clear() {
        coordinator.clear();
    }
}
