package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.world_modification.BiomeBuffer;
import com.moulberry.axiom.world_modification.Dispatcher;
import io.github.lumi.LumiMod;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/** The integrated dispatcher bypasses the remote biome handler. */
@Mixin(value = Dispatcher.class, remap = false)
abstract class AxiomIntegratedBiomeMixin {
    @WrapMethod(method = "applyBiomeBufferSingleplayer")
    private static void lumi$guardBiomes(BiomeBuffer buffer, ServerLevel level, Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(level)
                .orElseThrow(() -> new IllegalStateException("Lumi dimension runtime is not loaded"));
        if (runtime.freeze().isAuthorizedMutation()) { io.github.lumi.minecraft.runtime.AxiomBiomeCapture.nativeReplay(buffer, runtime); original.call(buffer, level); return; }
        if (!runtime.freeze().isMutationAllowed()) return;
        UUID action = io.github.lumi.minecraft.runtime.DirectLiveActionContext.current(runtime.liveActions()).orElseThrow();
        io.github.lumi.minecraft.runtime.AxiomBiomeCapture.run(buffer, runtime, runtime.liveActions().owner(action).orElseThrow(),
                () -> original.call(buffer, level));
    }
}
