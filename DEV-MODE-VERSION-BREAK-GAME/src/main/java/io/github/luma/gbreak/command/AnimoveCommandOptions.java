package io.github.luma.gbreak.command;

import io.github.luma.gbreak.server.animal.AnimalSelector;
import java.util.Optional;
import net.minecraft.util.math.Vec3d;

record AnimoveCommandOptions(Optional<Vec3d> returnPosition, AnimalSelector selector, boolean loop) {
}
