package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.world_modification.BiomeBuffer;
import io.github.lumi.LumiMod;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Biomes stay outside history but cannot change during an exact frozen operation. */
@Mixin(value = AxiomServerboundSetBuffer.class, remap = false)
abstract class AxiomBiomeBufferMixin {
    @WrapMethod(method = "applyBiomeBufferServer")
    private static void lumi$guardBiomes(BiomeBuffer buffer, ServerLevel level, Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(level)
                .orElseThrow(() -> new IllegalStateException("Lumi dimension runtime is not loaded"));
        if (runtime.freeze().isMutationAllowed()) original.call(buffer, level);
    }
}
