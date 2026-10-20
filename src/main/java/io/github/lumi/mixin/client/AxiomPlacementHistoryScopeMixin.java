package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.clipboard.Placement;
import io.github.lumi.client.integration.AxiomHistoryCaptureScope;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = Placement.class, remap = false)
abstract class AxiomPlacementHistoryScopeMixin {
    @WrapMethod(method = "pastePlacementWithoutStopping")
    private void lumi$gesture(boolean air, Operation<Void> original) {
        try (var ignored = AxiomHistoryCaptureScope.open()) { original.call(air); }
    }
}
