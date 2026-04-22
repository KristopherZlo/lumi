package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class CompareOverlaySurfaceResolver {

    private static final int NORTH_FACE = 1 << 0;
    private static final int SOUTH_FACE = 1 << 1;
    private static final int WEST_FACE = 1 << 2;
    private static final int EAST_FACE = 1 << 3;
    private static final int DOWN_FACE = 1 << 4;
    private static final int UP_FACE = 1 << 5;

    Set<Long> indexPositions(List<DiffBlockEntry> entries) {
        Set<Long> occupiedPositions = new HashSet<>((entries.size() * 4 / 3) + 1);
        for (DiffBlockEntry entry : entries) {
            occupiedPositions.add(pack(entry.pos()));
        }
        return Set.copyOf(occupiedPositions);
    }

    List<SurfaceBlock> resolve(List<DiffBlockEntry> visibleEntries, Set<Long> occupiedPositions) {
        if (visibleEntries.isEmpty()) {
            return List.of();
        }

        List<SurfaceBlock> surfaceBlocks = new ArrayList<>(visibleEntries.size());
        for (DiffBlockEntry entry : visibleEntries) {
            int faceMask = resolveExposedFaces(entry.pos(), occupiedPositions);
            if (faceMask != 0) {
                surfaceBlocks.add(new SurfaceBlock(entry, faceMask));
            }
        }
        return List.copyOf(surfaceBlocks);
    }

    private int resolveExposedFaces(BlockPoint pos, Set<Long> occupiedPositions) {
        int faceMask = 0;
        if (!occupied(occupiedPositions, pos.x(), pos.y(), pos.z() - 1)) {
            faceMask |= NORTH_FACE;
        }
        if (!occupied(occupiedPositions, pos.x(), pos.y(), pos.z() + 1)) {
            faceMask |= SOUTH_FACE;
        }
        if (!occupied(occupiedPositions, pos.x() - 1, pos.y(), pos.z())) {
            faceMask |= WEST_FACE;
        }
        if (!occupied(occupiedPositions, pos.x() + 1, pos.y(), pos.z())) {
            faceMask |= EAST_FACE;
        }
        if (!occupied(occupiedPositions, pos.x(), pos.y() - 1, pos.z())) {
            faceMask |= DOWN_FACE;
        }
        if (!occupied(occupiedPositions, pos.x(), pos.y() + 1, pos.z())) {
            faceMask |= UP_FACE;
        }
        return faceMask;
    }

    private static boolean occupied(Set<Long> occupiedPositions, int x, int y, int z) {
        return occupiedPositions.contains(BlockPos.asLong(x, y, z));
    }

    private static long pack(BlockPoint pos) {
        return BlockPos.asLong(pos.x(), pos.y(), pos.z());
    }

    record SurfaceBlock(DiffBlockEntry entry, int faceMask) {

        boolean northExposed() {
            return (this.faceMask & NORTH_FACE) != 0;
        }

        boolean southExposed() {
            return (this.faceMask & SOUTH_FACE) != 0;
        }

        boolean westExposed() {
            return (this.faceMask & WEST_FACE) != 0;
        }

        boolean eastExposed() {
            return (this.faceMask & EAST_FACE) != 0;
        }

        boolean downExposed() {
            return (this.faceMask & DOWN_FACE) != 0;
        }

        boolean upExposed() {
            return (this.faceMask & UP_FACE) != 0;
        }
    }
}
