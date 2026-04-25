package io.github.luma.gbreak.server.animal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

final class AnimalMovePlanTest {

    @Test
    void loopRequiresReturnPosition() {
        assertThrows(IllegalArgumentException.class, () -> new AnimalMovePlan(Vec3d.ZERO, Optional.empty(), true));
    }

    @Test
    void oneWayMoveDoesNotRequireReturnPosition() {
        assertDoesNotThrow(() -> new AnimalMovePlan(Vec3d.ZERO, Optional.empty(), false));
    }
}
