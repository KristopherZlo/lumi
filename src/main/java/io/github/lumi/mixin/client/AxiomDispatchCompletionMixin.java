package io.github.lumi.mixin.client;

import com.moulberry.axiom.world_modification.DispatchTask;
import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import io.github.lumi.client.integration.AxiomWorldHistory;
import io.github.lumi.domain.model.AxiomEntryIdentity;
import io.github.lumi.network.AxiomDispatchPayload;
import java.util.UUID;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native isDone means that its final buffer has been sent, not merely batched. */
@Mixin(value = {DispatchTask.Block.class, DispatchTask.Biome.class}, remap = false)
abstract class AxiomDispatchCompletionMixin {
    @Shadow(remap = false) @Final private ResourceKey<Level> dimension;
    @Shadow(remap = false) @Final private UUID uuid;
    @Unique private boolean lumi$announced;
    @Unique private boolean lumi$completed;
    @Unique private Optional<AxiomEntryIdentity> lumi$entry = Optional.empty();
    @Unique private io.github.lumi.client.integration.AxiomNativeReplayClient.Token lumi$replay;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lumi$identify(ResourceKey<Level> dimension, UUID dispatch, @Coerce BlockOrBiomeBuffer buffer, CallbackInfo callback) {
        var binding = AxiomWorldHistory.INSTANCE.nativeEntry(buffer);
        if (binding != null) lumi$entry = Optional.of(new AxiomEntryIdentity(binding.epoch(), binding.entry()));
        var replay = io.github.lumi.client.integration.AxiomNativeReplayClient.current();
        if (replay != null) lumi$replay = replay.retain();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void lumi$begin(int sends, CallbackInfoReturnable<Integer> callback) {
        if (!lumi$announced && ClientPlayNetworking.canSend(AxiomDispatchPayload.TYPE)) {
            ClientPlayNetworking.send(new AxiomDispatchPayload(uuid, dimension.identifier().toString(), false, lumi$entry, false,
                    Optional.ofNullable(lumi$replay).map(io.github.lumi.client.integration.AxiomNativeReplayClient.Token::request)));
            lumi$announced = true;
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void lumi$complete(int sends, CallbackInfoReturnable<Integer> callback) {
        if (lumi$announced && !lumi$completed && ((DispatchTask) (Object) this).isDone()) {
            ClientPlayNetworking.send(new AxiomDispatchPayload(uuid, dimension.identifier().toString(), true, lumi$entry, false,
                    Optional.ofNullable(lumi$replay).map(io.github.lumi.client.integration.AxiomNativeReplayClient.Token::request)));
            lumi$completed = true;
            if (lumi$replay != null) lumi$replay.release();
        }
    }
}
