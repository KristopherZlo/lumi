package io.github.lumi.mixin.client;

import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import com.moulberry.axiom.world_modification.Dispatcher;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import io.github.lumi.client.integration.AxiomWorldHistory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Display confirmed groups using native entry objects; never replace the native payload buffer. */
@Mixin(value = Dispatcher.class, remap = false)
abstract class AxiomNativeHistoryViewMixin {
    @Redirect(method = {"getHistoryData", "getHistoryDataCount", "getHistoryPosition", "getHistoryBytes", "getActiveSelectionHistoryStart"},
            at = @At(value = "FIELD", target = "Lcom/moulberry/axiom/world_modification/Dispatcher;history:Lcom/moulberry/axiom/world_modification/HistoryBuffer;"))
    private static HistoryBuffer<BlockOrBiomeBuffer> lumi$confirmedHistory() {
        return AxiomWorldHistory.INSTANCE.visibleHistory();
    }
}
