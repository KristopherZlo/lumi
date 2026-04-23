package io.github.luma.ui.controller;

import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.time.Duration;
import java.time.Instant;
import net.minecraft.server.MinecraftServer;

final class OperationSnapshotViewService {

    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    OperationSnapshot loadVisibleSnapshot(MinecraftServer server, String projectId) {
        return this.visibleSnapshot(this.worldOperationManager.snapshot(server, projectId).orElse(null));
    }

    OperationSnapshot visibleSnapshot(OperationSnapshot snapshot) {
        if (snapshot == null || !snapshot.terminal()) {
            return snapshot;
        }

        return Duration.between(snapshot.updatedAt(), Instant.now()).compareTo(Duration.ofSeconds(5)) <= 0
                ? snapshot
                : null;
    }
}
