package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import java.util.Optional;

/**
 * Owns active/recent operation registration for one server world.
 */
final class WorldOperationLifecycle {

    private final WorldOperationRegistry operationRegistry = new WorldOperationRegistry();

    boolean hasActive(String serverKey) {
        return this.operationRegistry.hasActive(serverKey);
    }

    WorldOperationManager.ActiveOperation active(String serverKey) {
        return this.operationRegistry.active(serverKey);
    }

    Optional<OperationSnapshot> snapshot(String serverKey) {
        return this.operationRegistry.snapshot(serverKey);
    }

    Optional<OperationSnapshot> snapshot(String serverKey, String projectId) {
        return this.operationRegistry.snapshot(serverKey, projectId);
    }

    Optional<OperationSnapshot> snapshot(String serverKey, OperationHandle handle) {
        return this.operationRegistry.snapshot(serverKey, handle);
    }

    Optional<String> applyMetrics(OperationHandle handle) {
        return this.operationRegistry.applyMetrics(handle);
    }

    void start(String serverKey, WorldOperationManager.ActiveOperation operation) {
        this.ensureIdle(serverKey);
        this.operationRegistry.putActive(serverKey, operation);
    }

    Completion complete(String serverKey, WorldOperationManager.ActiveOperation operation) {
        WorldOperationManager.ActiveOperation active = this.operationRegistry.active(serverKey);
        if (active != operation) {
            return Completion.ignored();
        }

        this.operationRegistry.removeActive(serverKey);
        Optional<String> metrics = this.operationRegistry.remember(serverKey, operation);
        WorldOperationManager.ActiveOperation followUp = operation.followUpOperation();
        if (followUp != null) {
            this.operationRegistry.putActive(serverKey, followUp);
        }
        return new Completion(true, followUp, metrics);
    }

    WorldOperationManager.ActiveOperation removeActive(String serverKey) {
        return this.operationRegistry.removeActive(serverKey);
    }

    Optional<String> remember(String serverKey, WorldOperationManager.ActiveOperation operation) {
        return this.operationRegistry.remember(serverKey, operation);
    }

    void ensureIdle(String serverKey) {
        if (this.operationRegistry.hasActive(serverKey)) {
            throw new IllegalStateException("Another world operation is already running");
        }
    }

    record Completion(
            boolean completed,
            WorldOperationManager.ActiveOperation followUp,
            Optional<String> metrics
    ) {

        private static Completion ignored() {
            return new Completion(false, null, Optional.empty());
        }
    }
}
