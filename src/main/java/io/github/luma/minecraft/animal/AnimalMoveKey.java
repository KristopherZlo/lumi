package io.github.luma.minecraft.animal;

import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

record AnimalMoveKey(ResourceKey<Level> levelKey, UUID animalId) {
}
