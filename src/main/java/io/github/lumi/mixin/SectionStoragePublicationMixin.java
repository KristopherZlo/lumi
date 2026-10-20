package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lumi.minecraft.world.StoragePublicationGate;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep dirty POI entries queued until speculative block changes have a durable intent. */
@Mixin(SectionStorage.class)
abstract class SectionStoragePublicationMixin implements StoragePublicationGate {
    @Unique private BooleanSupplier lumi$publicationAllowed = () -> true;

    @Override public void lumi$bindPublication(BooleanSupplier allowed) {
        lumi$publicationAllowed = Objects.requireNonNull(allowed);
    }

    @WrapMethod(method = "tick")
    private void lumi$gateTick(BooleanSupplier budget, Operation<Void> original) {
        original.call((BooleanSupplier) () -> lumi$publicationAllowed.getAsBoolean() && budget.getAsBoolean());
    }

    @Inject(method = {"flush", "flushAll"}, at = @At("HEAD"), cancellable = true)
    private void lumi$gateFlush(CallbackInfo callback) {
        // Cancel before vanilla removes dirty entries, rather than cancelling writeChunk afterwards.
        if (!lumi$publicationAllowed.getAsBoolean()) callback.cancel();
    }
}
