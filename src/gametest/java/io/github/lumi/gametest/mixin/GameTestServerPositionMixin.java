package io.github.lumi.gametest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Allows a failed randomized chunk layout to be reproduced without changing the player JAR. */
@Mixin(GameTestServer.class)
public abstract class GameTestServerPositionMixin {
    @ModifyVariable(method = "startTests", at = @At("STORE"), ordinal = 0)
    private BlockPos lumi$testPosition(BlockPos randomized) {
        String configured = System.getProperty("lumi.gametest.position");
        if (configured == null) return randomized;
        String[] coordinates = configured.split(",", -1);
        if (coordinates.length != 3) throw new IllegalArgumentException("Expected test position x,y,z");
        return new BlockPos(Integer.parseInt(coordinates[0]),
                Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
    }
}
