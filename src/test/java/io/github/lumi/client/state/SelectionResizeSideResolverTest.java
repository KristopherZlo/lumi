package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BlockPosition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SelectionResizeSideResolverTest {
    private static final BlockBox BOX = new BlockBox(0, 0, 0, 4, 4, 4);

    @Test
    void viewDirectionSelectsTheFaceThePlayerLooksToward() {
        assertEquals(SelectionSide.MIN_X,
                SelectionResizeSideResolver.resolve(
                        BOX, null, new Vec3(1, 0, 0)));
        assertEquals(SelectionSide.MAX_Y,
                SelectionResizeSideResolver.resolve(
                        BOX, null, new Vec3(0, -1, 0)));
        assertEquals(SelectionSide.MIN_Z,
                SelectionResizeSideResolver.resolve(
                        BOX, null, new Vec3(0, 0, 1)));
    }

    @Test
    void targetFallbackUsesOutsideThenNearestFace() {
        assertEquals(SelectionSide.MIN_X,
                SelectionResizeSideResolver.resolve(
                        BOX, new BlockPosition(-3, 2, 2), null));
        assertEquals(SelectionSide.MAX_Z,
                SelectionResizeSideResolver.resolve(
                        BOX, new BlockPosition(2, 2, 4), null));
    }

    @Test
    void scrollDirectionAccountsForWhichWayThePlayerFaces() {
        Vec3 eye = new Vec3(2.5, 2.5, -5);
        assertEquals(1, SelectionResizeSideResolver.amountForScroll(
                BOX, eye, new Vec3(0, 0, 1), -1));
        assertEquals(-1, SelectionResizeSideResolver.amountForScroll(
                BOX, eye, new Vec3(0, 0, -1), -1));
    }
}
