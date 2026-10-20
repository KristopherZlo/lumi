package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.buildertools.CloneBuilderTool;
import com.moulberry.axiom.buildertools.MoveBuilderTool;
import com.moulberry.axiom.buildertools.StackBuilderTool;
import io.github.lumi.client.integration.AxiomHistoryCaptureScope;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {CloneBuilderTool.class, MoveBuilderTool.class, StackBuilderTool.class}, remap = false)
abstract class AxiomBuilderHistoryScopeMixin {
    @WrapMethod(method = "apply")
    private void lumi$gesture(Operation<Void> original) {
        try (var ignored = AxiomHistoryCaptureScope.open()) { original.call(); }
    }
}
