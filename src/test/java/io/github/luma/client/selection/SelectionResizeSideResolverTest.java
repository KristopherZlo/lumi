package io.github.luma.client.selection;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelectionResizeSideResolverTest {

    @Test
    void choosesLookedFaceBeforeNearestTargetFace() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(10, 74, 10));

        LumiRegionSelectionState.Side side = SelectionResizeSideResolver.resolve(
                bounds,
                new BlockPoint(9, 68, 5),
                new Vec3(0.0D, 0.0D, 1.0D)
        );

        assertEquals(LumiRegionSelectionState.Side.MIN_Z, side);
    }

    @Test
    void fallsBackToOutsideAxisWithoutViewDirection() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(10, 74, 10));

        LumiRegionSelectionState.Side side = SelectionResizeSideResolver.resolve(
                bounds,
                new BlockPoint(-2, 68, 5),
                null
        );

        assertEquals(LumiRegionSelectionState.Side.MIN_X, side);
    }

    @Test
    void fallsBackToLookedFace() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(10, 74, 10));

        LumiRegionSelectionState.Side side = SelectionResizeSideResolver.resolve(
                bounds,
                null,
                new Vec3(0.0D, 0.0D, -1.0D)
        );

        assertEquals(LumiRegionSelectionState.Side.MAX_Z, side);
    }

    @Test
    void scrollDownPullsTowardPlayerWhenLookingAtSelection() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(10, 74, 10));

        int amount = SelectionResizeSideResolver.amountForScroll(
                bounds,
                new Vec3(5.0D, 69.0D, -10.0D),
                new Vec3(0.0D, 0.0D, 1.0D),
                -1.0D
        );

        assertEquals(1, amount);
    }

    @Test
    void scrollDownStillPullsTowardPlayerWhenLookingAwayFromSelection() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(10, 74, 10));

        int amount = SelectionResizeSideResolver.amountForScroll(
                bounds,
                new Vec3(5.0D, 69.0D, -10.0D),
                new Vec3(0.0D, 0.0D, -1.0D),
                -1.0D
        );

        assertEquals(-1, amount);
    }
}
