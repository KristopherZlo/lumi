package io.github.luma.client.selection;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import net.minecraft.world.phys.Vec3;

final class SelectionResizeSideResolver {

    private SelectionResizeSideResolver() {
    }

    static LumiRegionSelectionState.Side resolve(Bounds3i bounds, BlockPoint target, Vec3 view) {
        if (view != null) {
            return lookedFace(view);
        }
        if (bounds != null && target != null) {
            LumiRegionSelectionState.Side outside = outsideSide(bounds, target);
            return outside == null ? nearestFace(bounds, target) : outside;
        }
        return LumiRegionSelectionState.Side.MAX_Z;
    }

    static int amountForScroll(Bounds3i bounds, Vec3 eye, Vec3 view, double scroll) {
        int amount = scroll < 0.0D ? 1 : -1;
        return looksTowardSelection(bounds, eye, view) ? amount : -amount;
    }

    private static LumiRegionSelectionState.Side outsideSide(Bounds3i bounds, BlockPoint target) {
        Candidate best = null;
        best = closer(best, target.x() < bounds.min().x(), bounds.min().x() - target.x(), LumiRegionSelectionState.Side.MIN_X);
        best = closer(best, target.x() > bounds.max().x(), target.x() - bounds.max().x(), LumiRegionSelectionState.Side.MAX_X);
        best = closer(best, target.y() < bounds.min().y(), bounds.min().y() - target.y(), LumiRegionSelectionState.Side.MIN_Y);
        best = closer(best, target.y() > bounds.max().y(), target.y() - bounds.max().y(), LumiRegionSelectionState.Side.MAX_Y);
        best = closer(best, target.z() < bounds.min().z(), bounds.min().z() - target.z(), LumiRegionSelectionState.Side.MIN_Z);
        best = closer(best, target.z() > bounds.max().z(), target.z() - bounds.max().z(), LumiRegionSelectionState.Side.MAX_Z);
        return best == null ? null : best.side();
    }

    private static LumiRegionSelectionState.Side nearestFace(Bounds3i bounds, BlockPoint target) {
        Candidate best = null;
        best = closer(best, true, target.x() - bounds.min().x(), LumiRegionSelectionState.Side.MIN_X);
        best = closer(best, true, bounds.max().x() - target.x(), LumiRegionSelectionState.Side.MAX_X);
        best = closer(best, true, target.y() - bounds.min().y(), LumiRegionSelectionState.Side.MIN_Y);
        best = closer(best, true, bounds.max().y() - target.y(), LumiRegionSelectionState.Side.MAX_Y);
        best = closer(best, true, target.z() - bounds.min().z(), LumiRegionSelectionState.Side.MIN_Z);
        best = closer(best, true, bounds.max().z() - target.z(), LumiRegionSelectionState.Side.MAX_Z);
        return best.side();
    }

    private static LumiRegionSelectionState.Side lookedFace(Vec3 view) {
        if (view == null) {
            return LumiRegionSelectionState.Side.MAX_Z;
        }
        double absX = Math.abs(view.x);
        double absY = Math.abs(view.y);
        double absZ = Math.abs(view.z);
        if (absX >= absY && absX >= absZ) {
            return view.x >= 0.0D ? LumiRegionSelectionState.Side.MIN_X : LumiRegionSelectionState.Side.MAX_X;
        }
        if (absY >= absZ) {
            return view.y >= 0.0D ? LumiRegionSelectionState.Side.MIN_Y : LumiRegionSelectionState.Side.MAX_Y;
        }
        return view.z >= 0.0D ? LumiRegionSelectionState.Side.MIN_Z : LumiRegionSelectionState.Side.MAX_Z;
    }

    private static boolean looksTowardSelection(Bounds3i bounds, Vec3 eye, Vec3 view) {
        if (bounds == null || eye == null || view == null) {
            return true;
        }
        Vec3 center = new Vec3(
                (bounds.min().x() + bounds.max().x() + 1.0D) / 2.0D,
                (bounds.min().y() + bounds.max().y() + 1.0D) / 2.0D,
                (bounds.min().z() + bounds.max().z() + 1.0D) / 2.0D
        );
        return center.subtract(eye).dot(view) >= 0.0D;
    }

    private static Candidate closer(
            Candidate best,
            boolean active,
            int distance,
            LumiRegionSelectionState.Side side
    ) {
        if (!active) {
            return best;
        }
        int normalizedDistance = Math.abs(distance);
        return best == null || normalizedDistance < best.distance()
                ? new Candidate(normalizedDistance, side)
                : best;
    }

    private record Candidate(int distance, LumiRegionSelectionState.Side side) {
    }
}
