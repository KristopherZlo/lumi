package io.github.luma.minecraft.command;

import io.github.luma.minecraft.animal.AnimalSelector;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

record AnimalMoveCommandOptions(Optional<Vec3> returnPosition, AnimalSelector selector, boolean loop) {
}
