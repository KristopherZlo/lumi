package io.github.luma.gbreak.server.animal;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.util.math.Vec3d;

public record AnimalMovePlan(Vec3d destination, Optional<Vec3d> returnPosition, boolean loop) {

    public AnimalMovePlan {
        Objects.requireNonNull(destination, "destination");
        returnPosition = Objects.requireNonNull(returnPosition, "returnPosition");
        if (loop && returnPosition.isEmpty()) {
            throw new IllegalArgumentException("Looping animal movement requires a return position");
        }
    }
}
