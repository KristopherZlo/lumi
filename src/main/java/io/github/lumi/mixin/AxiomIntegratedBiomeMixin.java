package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.world_modification.BiomeBuffer;
import com.moulberry.axiom.world_modification.Dispatcher;
import io.github.lumi.LumiMod;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/** The integrated dispatcher bypasses the remote biome handler. */
@Mixin(value = Dispatcher.class, remap = false)
abstract class AxiomIntegratedBiomeMixin {
    @WrapMethod(method = "applyBiomeBufferSingleplayer")
    private static void lumi$guardBiomes(BiomeBuffer buffer, ServerLevel level, Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(level)
                .orElseThrow(() -> new IllegalStateException("Lumi dimension runtime is not loaded"));
        if (runtime.freeze().isMutationAllowed()) original.call(buffer, level);
    }
}
