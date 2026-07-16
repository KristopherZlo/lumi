package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.render.regions.ChunkedBooleanRegion;
import com.moulberry.axiom.world_modification.BlockBuffer;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Makes one direct Axiom section-buffer write one durable, live-undoable action. */
@Mixin(value = AxiomServerboundSetBuffer.class, remap = false)
abstract class AxiomSetBufferMixin {
    private static final long LUMI_MAX_CAPTURE_BYTES = 64L << 20;
    private static final MinecraftSectionCapture LUMI_SECTIONS = new MinecraftSectionCapture();

    @WrapMethod(method = "applyBlockBufferServer")
    private static void lumi$trackBuffer(
            BlockBuffer buffer,
            ServerLevel level,
            ChunkedBooleanRegion mask,
            ServerPlayer player,
            Operation<Void> original) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(level).orElse(null);
        if (runtime == null || player == null) {
            original.call(buffer, level, mask, player);
            return;
        }
        if (!runtime.freeze().isMutationAllowed()) {
            return;
        }
        Map<BlockPosition, BlockSnapshot> before = new LinkedHashMap<>();
        Set<SectionKey> sections = new LinkedHashSet<>();
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), player.getUUID())) {
            UUID action = DirectLiveActionContext.current(runtime.liveActions()).orElseThrow();
            captureBefore(buffer, mask, runtime, action, before, sections);
            registerDurability(level, runtime, sections);
            try {
                original.call(buffer, level, mask, player);
            } finally {
                recordAfter(runtime, action, before);
            }
        }
    }

    private static void captureBefore(
            BlockBuffer buffer,
            ChunkedBooleanRegion mask,
            FabricDimensionRuntime runtime,
            UUID action,
            Map<BlockPosition, BlockSnapshot> before,
            Set<SectionKey> sections) {
        long[] retainedBytes = {0};
        boolean[] available = {buffer.estimateSizeInRAM() <= LUMI_MAX_CAPTURE_BYTES};
        if (!available[0]) {
            runtime.liveActions().makeUnavailable(
                    action, "Axiom edit exceeded its live capture limit");
        }
        buffer.forEach((x, y, z, state) -> {
            if (state == BlockBuffer.EMPTY_STATE || mask != null && !mask.contains(x, y, z)) {
                return;
            }
            BlockPosition position = new BlockPosition(x, y, z);
            if (available[0]) {
                try {
                    BlockSnapshot snapshot = runtime.liveWorld().read(position);
                    long bytes = 128L
                            + snapshot.blockState().getBytes(StandardCharsets.UTF_8).length
                            + snapshot.blockEntity().map(nbt -> nbt.bytes().length).orElse(0);
                    if (retainedBytes[0] + bytes > LUMI_MAX_CAPTURE_BYTES) {
                        available[0] = false;
                        before.clear();
                        runtime.liveActions().makeUnavailable(
                                action, "Axiom edit exceeded its live capture limit");
                    } else {
                        retainedBytes[0] += bytes;
                        before.put(position, snapshot);
                    }
                } catch (IOException failed) {
                    throw new UncheckedIOException("Cannot capture Axiom block before mutation", failed);
                }
            }
            sections.add(MinecraftSectionCapture.key(new BlockPos(x, y, z)));
        });
    }

    private static void registerDurability(
            ServerLevel level,
            FabricDimensionRuntime runtime,
            Set<SectionKey> sections) {
        for (SectionKey key : sections) {
            runtime.mutations().registerSectionMutation(key, () -> {
                try {
                    return LUMI_SECTIONS.capture(
                            level, level.getChunk(key.chunkX(), key.chunkZ()), key.sectionY());
                } catch (IOException failed) {
                    throw new UncheckedIOException("Cannot capture Axiom section origin", failed);
                }
            });
            runtime.blockEntityBaselines().discard(key);
        }
    }

    private static void recordAfter(
            FabricDimensionRuntime runtime,
            UUID action,
            Map<BlockPosition, BlockSnapshot> before) {
        before.forEach((position, snapshot) -> {
            try {
                BlockSnapshot after = runtime.liveWorld().read(position);
                if (!snapshot.equals(after)) {
                    runtime.liveActions().record(action, position, snapshot, after);
                    runtime.recordCausalZoneGrowth(action, position);
                }
            } catch (IOException failed) {
                throw new UncheckedIOException("Cannot capture Axiom block after mutation", failed);
            }
        });
    }
}
