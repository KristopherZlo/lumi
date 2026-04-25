package io.github.luma.gbreak.server.animal;

import java.util.UUID;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

record AnimalMoveKey(RegistryKey<World> worldKey, UUID animalId) {
}
