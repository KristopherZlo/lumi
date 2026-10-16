package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.AxiomServer;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.restrictions.AxiomPermission;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.MinecraftEntityMutationCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Captures native NBT, pose and riding edits, including both sides of a passenger transfer. */
@Mixin(value = AxiomServerboundManipulateEntity.class, remap = false)
abstract class AxiomManipulateEntityMixin {
    @Shadow @Final private List<AxiomServerboundManipulateEntity.ManipulateEntry> entries;

    @WrapMethod(method = "handle")
    private void lumi$captureManipulation(MinecraftServer server, ServerPlayer player, Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(player.level())
                .orElseThrow(() -> new IllegalStateException("Lumi dimension runtime is not loaded"));
        if (!runtime.freeze().isMutationAllowed()) return;
        if (!AxiomServer.canUseAxiom(player, AxiomPermission.ENTITY_MANIPULATE)) {
            original.call(server, player);
            return;
        }
        var affected = new LinkedHashSet<UUID>();
        for (var entry : entries) {
            affected.add(entry.uuid());
            affected.addAll(entry.passengers());
        }
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID());
                var capture = new MinecraftEntityMutationCapture(runtime)) {
            capture.capture(affected.stream().map(player.level()::getEntity).filter(Objects::nonNull).toList());
            original.call(server, player);
        } catch (IOException failed) {
            throw new UncheckedIOException("Cannot capture Axiom entity manipulation", failed);
        }
    }
}
