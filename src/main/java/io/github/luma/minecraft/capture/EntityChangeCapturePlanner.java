package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Resolves one entity mutation into live-history and durable-capture work. */
final class EntityChangeCapturePlanner {

    private final EntityMutationCapturePolicy capturePolicy = new EntityMutationCapturePolicy();
    private final TrackedProjectCatalog projectCatalog;

    EntityChangeCapturePlanner(TrackedProjectCatalog projectCatalog) {
        this.projectCatalog = projectCatalog;
    }

    CapturePlan plan(EntityPayload oldPayload, EntityPayload newPayload, Instant actionStartedAt) {
        WorldMutationSource source = WorldMutationContext.currentSource();
        Optional<StoredEntityChange> durable = this.capturePolicy.capture(source, oldPayload, newPayload);
        Optional<StoredEntityChange> live = WorldMutationContext.currentActionId().isBlank()
                ? Optional.empty()
                : this.capturePolicy.captureUndoRedo(source, oldPayload, newPayload);
        if (durable.isEmpty() && live.isEmpty()) {
            return null;
        }

        List<BlockPos> positions = this.positions(oldPayload, newPayload);
        return new CapturePlan(
                source,
                positions.getFirst(),
                positions,
                positions.stream().map(ChunkPoint::from).distinct().toList(),
                durable,
                live,
                actionStartedAt,
                Instant.now()
        );
    }

    List<TrackedProject> matchingProjects(ServerLevel level, CapturePlan plan) throws IOException {
        LinkedHashMap<String, TrackedProject> projects = new LinkedHashMap<>();
        for (BlockPos pos : plan.positions()) {
            for (TrackedProject trackedProject : this.projectCatalog.matching(level, pos)) {
                projects.putIfAbsent(trackedProject.project().id().toString(), trackedProject);
            }
        }
        return List.copyOf(projects.values());
    }

    private List<BlockPos> positions(EntityPayload oldPayload, EntityPayload newPayload) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        if (newPayload != null) {
            positions.add(newPayload.blockPos());
        }
        if (oldPayload != null) {
            positions.add(oldPayload.blockPos());
        }
        if (positions.isEmpty()) {
            positions.add(BlockPos.ZERO);
        }
        return List.copyOf(positions);
    }

    record CapturePlan(
            WorldMutationSource source,
            BlockPos primaryPos,
            List<BlockPos> positions,
            List<ChunkPoint> chunks,
            Optional<StoredEntityChange> durableMutation,
            Optional<StoredEntityChange> liveMutation,
            Instant actionStartedAt,
            Instant capturedAt
    ) {
    }
}
