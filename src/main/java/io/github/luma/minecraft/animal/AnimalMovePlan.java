package io.github.luma.minecraft.animal;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable route requested by the animove development command.
 */
public record AnimalMovePlan(Vec3 destination, Optional<Vec3> returnPosition, boolean loop) {

    public AnimalMovePlan {
        Objects.requireNonNull(destination, "destination");
        returnPosition = Objects.requireNonNull(returnPosition, "returnPosition");
        if (loop && returnPosition.isEmpty()) {
            throw new IllegalArgumentException("Looping animal movement requires a return position");
        }
    }
}
