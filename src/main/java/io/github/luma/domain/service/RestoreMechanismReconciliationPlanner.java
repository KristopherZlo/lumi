package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.minecraft.world.MechanismReplayScope;
import io.github.luma.minecraft.world.MechanismStatePolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * Bounds redstone/mechanism target-state reconciliation for restore and
 * rollback prepare paths.
 */
final class RestoreMechanismReconciliationPlanner {

    static final int MAX_MECHANISM_RECONCILIATION_CELLS = 65_536;

    private final MechanismStatePolicy mechanismStatePolicy = new MechanismStatePolicy();

    Optional<List<BlockPoint>> boundedExactRootReplayPositions(
            io.github.luma.domain.model.BuildProject project,
            MechanismReplayScope mechanismScope,
            List<BlockPoint> existingPositions,
            ServerLevel level
    ) {
        RestoreMechanismReplaySelection selection = this.selectExactRootReplayPositions(
                project,
                mechanismScope,
                existingPositions,
                level
        );
        if (selection.truncatedMechanismScope()) {
            return Optional.empty();
        }
        return Optional.of(selection.positions());
    }

    RestoreMechanismReplaySelection selectExactRootReplayPositions(
            io.github.luma.domain.model.BuildProject project,
            MechanismReplayScope mechanismScope,
            List<BlockPoint> existingPositions,
            ServerLevel level
    ) {
        LinkedHashMap<String, BlockPoint> selected = new LinkedHashMap<>();
        for (BlockPoint position : existingPositions == null ? List.<BlockPoint>of() : existingPositions) {
            selected.putIfAbsent(positionKey(position), position);
        }
        if (mechanismScope == null || mechanismScope.isEmpty()) {
            return new RestoreMechanismReplaySelection(List.copyOf(selected.values()), false);
        }
        Optional<List<BlockPoint>> mechanismPositions =
                this.boundedMechanismReplayPositions(project, mechanismScope, level);
        if (mechanismPositions.isEmpty()) {
            return new RestoreMechanismReplaySelection(List.copyOf(selected.values()), true);
        }
        for (BlockPoint position : mechanismPositions.orElseThrow()) {
            selected.putIfAbsent(positionKey(position), position);
        }
        return new RestoreMechanismReplaySelection(List.copyOf(selected.values()), false);
    }

    Optional<List<BlockPoint>> boundedMechanismReplayPositions(
            io.github.luma.domain.model.BuildProject project,
            MechanismReplayScope mechanismScope,
            ServerLevel level
    ) {
        LinkedHashMap<String, BlockPoint> selected = new LinkedHashMap<>();
        if (mechanismScope == null || mechanismScope.isEmpty()) {
            return Optional.of(List.of());
        }
        int extraCells = 0;
        for (BlockPoint position : mechanismScope.positions()) {
            if (!this.insideMechanismReconciliationBounds(project, position, level)) {
                continue;
            }
            if (!selected.containsKey(positionKey(position))) {
                extraCells += 1;
                if (extraCells > MAX_MECHANISM_RECONCILIATION_CELLS) {
                    return Optional.empty();
                }
            }
            selected.putIfAbsent(positionKey(position), position);
        }
        for (ChunkSectionPoint section : mechanismScope.sections()) {
            int baseX = section.chunkX() << 4;
            int baseZ = section.chunkZ() << 4;
            int baseY = section.sectionY() << 4;
            for (int localY = 0; localY < 16; localY += 1) {
                for (int localZ = 0; localZ < 16; localZ += 1) {
                    for (int localX = 0; localX < 16; localX += 1) {
                        BlockPoint position = new BlockPoint(baseX + localX, baseY + localY, baseZ + localZ);
                        if (!this.insideMechanismReconciliationBounds(project, position, level)) {
                            continue;
                        }
                        if (!selected.containsKey(positionKey(position))) {
                            extraCells += 1;
                            if (extraCells > MAX_MECHANISM_RECONCILIATION_CELLS) {
                                return Optional.empty();
                            }
                        }
                        selected.putIfAbsent(positionKey(position), position);
                    }
                }
            }
        }
        return Optional.of(List.copyOf(selected.values()));
    }

    boolean containsMechanismState(List<StoredBlockChange> changes) {
        for (StoredBlockChange change : changes == null ? List.<StoredBlockChange>of() : changes) {
            if (this.mechanismStatePolicy.isMechanismPayload(change.oldValue())
                    || this.mechanismStatePolicy.isMechanismPayload(change.newValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean insideMechanismReconciliationBounds(
            io.github.luma.domain.model.BuildProject project,
            BlockPoint position,
            ServerLevel level
    ) {
        if (position == null) {
            return false;
        }
        Bounds3i bounds = project == null ? null : project.bounds();
        if (bounds != null && !bounds.contains(position)) {
            return false;
        }
        return level == null || (position.y() >= level.getMinY() && position.y() < level.getMaxY());
    }

    private static String positionKey(BlockPoint position) {
        return position.x() + ":" + position.y() + ":" + position.z();
    }
}

record RestoreMechanismReplaySelection(List<BlockPoint> positions, boolean truncatedMechanismScope) {

    RestoreMechanismReplaySelection {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
