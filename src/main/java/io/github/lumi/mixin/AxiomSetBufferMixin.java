package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.render.regions.ChunkedBooleanRegion;
import com.moulberry.axiom.world_modification.BlockBuffer;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.AxiomBufferDispatchContext;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Captures real compact section endpoints instead of per-block maps or hash-only fallback. */
@Mixin(value = AxiomServerboundSetBuffer.class, remap = false)
abstract class AxiomSetBufferMixin {
    private static final MinecraftSectionCapture LUMI_SECTIONS = new MinecraftSectionCapture();

    @WrapMethod(method = "applyBlockBufferServer")
    private static void lumi$trackBuffer(BlockBuffer buffer, ServerLevel level,
            ChunkedBooleanRegion changedRegion, ServerPlayer player, Operation<Void> original) {
        var runtime = LumiMod.serverRuntime().find(level)
                .orElseThrow(() -> new IllegalStateException("Lumi dimension runtime is not loaded"));
        if (player == null || player.level() != level || !runtime.freeze().isMutationAllowed()) return;
        var keys = new LinkedHashSet<SectionKey>();
        buffer.forEach((x, y, z, state) -> {
            if (state != BlockBuffer.EMPTY_STATE) keys.add(new SectionKey(x >> 4, y >> 4, z >> 4));
        });
        try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
            var action = DirectLiveActionContext.current(runtime.liveActions()).orElseThrow();
            AxiomBufferDispatchContext.current(player).ifPresent(dispatch -> runtime.axiomBufferActions()
                    .attach(player.connection, player.getUUID(), dispatch, action));
            Map<SectionKey, SectionBlob> before = new LinkedHashMap<>();
            for (var key : keys) {
                var captured = capture(level, key);
                before.put(key, captured);
                runtime.mutations().registerSectionMutation(key, () -> captured);
                runtime.blockEntityBaselines().discard(key);
            }
            Throwable applyFailure = null;
            try { original.call(buffer, level, changedRegion, player); }
            catch (RuntimeException | Error failure) { applyFailure = failure; throw failure; }
            finally {
                try {
                    for (var entry : before.entrySet()) {
                        var after = capture(level, entry.getKey());
                        runtime.liveActions().recordSection(action, entry.getKey(), entry.getValue(), after);
                        if (!entry.getValue().equals(after)) runtime.mutations().markBuilderMutation(entry.getKey());
                        runtime.liveBlocks().rebase(entry.getKey(), after);
                    }
                } catch (RuntimeException | Error failure) {
                    runtime.liveActions().makeUnavailable(action, "Cannot verify the Axiom section endpoints");
                    if (applyFailure == null) throw failure;
                    applyFailure.addSuppressed(failure);
                }
            }
        }
    }

    private static SectionBlob capture(ServerLevel level, SectionKey key) {
        try { return LUMI_SECTIONS.capture(level, level.getChunk(key.chunkX(), key.chunkZ()), key.sectionY()); }
        catch (IOException failure) { throw new UncheckedIOException("Cannot capture Axiom section", failure); }
    }
}
