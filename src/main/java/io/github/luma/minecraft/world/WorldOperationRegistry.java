package io.github.luma.minecraft.world;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class WorldOperationRegistry {

    private static final int LAST_OPERATION_LIMIT = 32;

    private final Map<String, WorldOperationManager.ActiveOperation> activeOperations = new HashMap<>();
    private final Map<String, OperationSnapshot> lastSnapshots = new HashMap<>();
    private final Map<String, OperationSnapshot> lastSnapshotsByOperationId = new LinkedHashMap<>();
    private final Map<String, String> lastApplyMetrics = new LinkedHashMap<>();

    boolean hasActive(String serverKey) {
        return this.activeOperations.containsKey(serverKey);
    }

    WorldOperationManager.ActiveOperation active(String serverKey) {
        return this.activeOperations.get(serverKey);
    }

    void putActive(String serverKey, WorldOperationManager.ActiveOperation operation) {
        this.activeOperations.put(serverKey, operation);
    }

    WorldOperationManager.ActiveOperation removeActive(String serverKey) {
        return this.activeOperations.remove(serverKey);
    }

    Optional<OperationSnapshot> snapshot(String serverKey) {
        WorldOperationManager.ActiveOperation active = this.active(serverKey);
        if (active != null) {
            return Optional.of(active.snapshot());
        }
        return Optional.ofNullable(this.lastSnapshots.get(serverKey));
    }

    Optional<OperationSnapshot> snapshot(String serverKey, String projectId) {
        return this.snapshot(serverKey)
                .filter(snapshot -> snapshot.handle() != null)
                .filter(snapshot -> projectId == null || projectId.equals(snapshot.handle().projectId()));
    }

    Optional<OperationSnapshot> snapshot(String serverKey, OperationHandle handle) {
        if (handle == null || handle.id() == null || handle.id().isBlank()) {
            return Optional.empty();
        }
        WorldOperationManager.ActiveOperation active = this.active(serverKey);
        if (active != null
                && active.handle() != null
                && handle.id().equals(active.handle().id())) {
            return Optional.of(active.snapshot());
        }
        return Optional.ofNullable(this.lastSnapshotsByOperationId.get(handle.id()))
                .filter(snapshot -> snapshot.handle() != null)
                .filter(snapshot -> handle.projectId() == null
                        || handle.projectId().equals(snapshot.handle().projectId()));
    }

    Optional<String> applyMetrics(OperationHandle handle) {
        if (handle == null || handle.id() == null || handle.id().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.lastApplyMetrics.get(handle.id()));
    }

    Optional<String> remember(String serverKey, WorldOperationManager.ActiveOperation operation) {
        this.lastSnapshots.put(serverKey, operation.snapshot());
        this.lastSnapshotsByOperationId.put(operation.handle().id(), operation.snapshot());
        Optional<String> metrics = operation.applyMetricsSummary();
        metrics.ifPresent(value -> this.lastApplyMetrics.put(operation.handle().id(), value));
        this.trimRememberedOperations();
        return metrics;
    }

    private void trimRememberedOperations() {
        while (this.lastSnapshotsByOperationId.size() > LAST_OPERATION_LIMIT) {
            String operationId = this.lastSnapshotsByOperationId.keySet().iterator().next();
            this.lastSnapshotsByOperationId.remove(operationId);
            this.lastApplyMetrics.remove(operationId);
        }
        while (this.lastApplyMetrics.size() > LAST_OPERATION_LIMIT) {
            String operationId = this.lastApplyMetrics.keySet().iterator().next();
            this.lastApplyMetrics.remove(operationId);
        }
    }
}
