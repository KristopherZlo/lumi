package io.github.lumi.client.state;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import net.minecraft.world.phys.Vec3;

/** Resolves the legacy looked-at or nearest face for wheel resizing. */
public final class SelectionResizeSideResolver {
    private SelectionResizeSideResolver() {
    }

    public static SelectionSide resolve(
            BlockBox bounds, BlockPosition target, Vec3 view) {
        if (view != null) {
            return lookedFace(view);
        }
        if (bounds != null && target != null) {
            SelectionSide outside = outsideSide(bounds, target);
            return outside == null ? nearestFace(bounds, target) : outside;
        }
        return SelectionSide.MAX_Z;
    }

    public static int amountForScroll(
            BlockBox bounds, Vec3 eye, Vec3 view, double scroll) {
        int amount = scroll < 0.0D ? 1 : -1;
        return looksTowardSelection(bounds, eye, view) ? amount : -amount;
    }

    private static SelectionSide outsideSide(
            BlockBox bounds, BlockPosition target) {
        Candidate best = null;
        best = closer(best, target.x() < bounds.minX(),
                bounds.minX() - target.x(), SelectionSide.MIN_X);
        best = closer(best, target.x() > bounds.maxX(),
                target.x() - bounds.maxX(), SelectionSide.MAX_X);
        best = closer(best, target.y() < bounds.minY(),
                bounds.minY() - target.y(), SelectionSide.MIN_Y);
        best = closer(best, target.y() > bounds.maxY(),
                target.y() - bounds.maxY(), SelectionSide.MAX_Y);
        best = closer(best, target.z() < bounds.minZ(),
                bounds.minZ() - target.z(), SelectionSide.MIN_Z);
        best = closer(best, target.z() > bounds.maxZ(),
                target.z() - bounds.maxZ(), SelectionSide.MAX_Z);
        return best == null ? null : best.side();
    }

    private static SelectionSide nearestFace(
            BlockBox bounds, BlockPosition target) {
        Candidate best = null;
        best = closer(best, true,
                target.x() - bounds.minX(), SelectionSide.MIN_X);
        best = closer(best, true,
                bounds.maxX() - target.x(), SelectionSide.MAX_X);
        best = closer(best, true,
                target.y() - bounds.minY(), SelectionSide.MIN_Y);
        best = closer(best, true,
                bounds.maxY() - target.y(), SelectionSide.MAX_Y);
        best = closer(best, true,
                target.z() - bounds.minZ(), SelectionSide.MIN_Z);
        best = closer(best, true,
                bounds.maxZ() - target.z(), SelectionSide.MAX_Z);
        return best.side();
    }

    private static SelectionSide lookedFace(Vec3 view) {
        double x = Math.abs(view.x);
        double y = Math.abs(view.y);
        double z = Math.abs(view.z);
        if (x >= y && x >= z) {
            return view.x >= 0.0D ? SelectionSide.MIN_X : SelectionSide.MAX_X;
        }
        if (y >= z) {
            return view.y >= 0.0D ? SelectionSide.MIN_Y : SelectionSide.MAX_Y;
        }
        return view.z >= 0.0D ? SelectionSide.MIN_Z : SelectionSide.MAX_Z;
    }

    private static boolean looksTowardSelection(
            BlockBox bounds, Vec3 eye, Vec3 view) {
        if (bounds == null || eye == null || view == null) {
            return true;
        }
        Vec3 center = new Vec3(
                (bounds.minX() + bounds.maxX() + 1.0D) / 2.0D,
                (bounds.minY() + bounds.maxY() + 1.0D) / 2.0D,
                (bounds.minZ() + bounds.maxZ() + 1.0D) / 2.0D);
        return center.subtract(eye).dot(view) >= 0.0D;
    }

    private static Candidate closer(
            Candidate best, boolean active, int distance, SelectionSide side) {
        if (!active) return best;
        int normalized = Math.abs(distance);
        return best == null || normalized < best.distance()
                ? new Candidate(normalized, side) : best;
    }

    private record Candidate(int distance, SelectionSide side) {
    }
}
