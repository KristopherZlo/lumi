package io.github.luma.mixin.client;

import io.github.luma.client.world.WorldEntryWarningController;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public final class WorldOpenFlowsMixin {

    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void luma$warnBeforeOpeningPreLumiWorld(String levelId, Runnable onFailure, CallbackInfo callback) {
        WorldOpenFlows flows = (WorldOpenFlows) (Object) this;
        if (WorldEntryWarningController.getInstance().showWarningIfNeeded(flows, levelId, onFailure)) {
            callback.cancel();
        }
    }

    @Inject(
            method = "createFreshLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;doWorldLoad("
                            + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                            + "Lnet/minecraft/server/packs/repository/PackRepository;"
                            + "Lnet/minecraft/server/WorldStem;Z)V"
            )
    )
    private void luma$markFreshWorldAsCreatedWithLumi(
            String levelId,
            LevelSettings settings,
            WorldOptions options,
            Function<HolderLookup.Provider, WorldDimensions> dimensionsFactory,
            Screen lastScreen,
            CallbackInfo callback
    ) {
        WorldEntryWarningController.getInstance().markCreatedWithLumi(levelId);
    }
}
