package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.render.regions.ChunkedBooleanRegion;
import com.moulberry.axiom.world_modification.BlockBuffer;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.minecraft.runtime.AxiomBufferDispatchContext;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import io.github.lumi.storage.repository.WorldObjectRepository;
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
            ChunkedBooleanRegion changedRegion,
            ServerPlayer player,
            Operation<Void> original) {
        FabricDimensionRuntime runtime = LumiMod.serverRuntime().find(level)
                .orElseThrow(() -> new IllegalStateException("Lumi dimension runtime is not loaded"));
        if (player == null || player.level() != level || !runtime.freeze().isMutationAllowed()) {
            return;
        }
        Map<BlockPosition, BlockSnapshot> before = new LinkedHashMap<>();
        Set<SectionKey> sections = new LinkedHashSet<>();
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), player.getUUID())) {
            UUID action = DirectLiveActionContext.current(runtime.liveActions()).orElseThrow();
            AxiomBufferDispatchContext.current(player).ifPresent(dispatch -> runtime.axiomBufferActions()
                    .attach(player.connection, player.getUUID(), dispatch, action));
            boolean exactCapture = captureBefore(
                    buffer, level, runtime, before, sections);
            Map<SectionKey, Long> generations =
                    registerDurability(level, runtime, sections);
            Map<SectionKey, ObjectId> fallbackBefore = exactCapture
                    ? Map.of() : captureSectionIds(level, runtime, sections);
            Throwable applyFailure = null;
            try {
                original.call(buffer, level, changedRegion, player);
            } catch (RuntimeException | Error failed) {
                applyFailure = failed;
                throw failed;
            } finally {
                try {
                    if (exactCapture) {
                        recordAfter(level, runtime, action, before, generations);
                    } else {
                        recordFallback(level, runtime, action, fallbackBefore);
                    }
                } catch (RuntimeException | Error captureFailure) {
                    if (applyFailure == null) throw captureFailure;
                    if (captureFailure != applyFailure) applyFailure.addSuppressed(captureFailure);
                }
            }
        }
    }

    private static boolean captureBefore(
            BlockBuffer buffer,
            ServerLevel level,
            FabricDimensionRuntime runtime,
            Map<BlockPosition, BlockSnapshot> before,
            Set<SectionKey> sections) {
        long[] retainedBytes = {0};
        boolean[] available = {buffer.estimateSizeInRAM() <= LUMI_MAX_CAPTURE_BYTES};
        buffer.forEach((x, y, z, state) -> {
            if (state == BlockBuffer.EMPTY_STATE) {
                return;
            }
            BlockPosition position = new BlockPosition(x, y, z);
            if (available[0]) {
                try {
                    // Native Axiom loads cold destinations; populate its chunk cache before the live read.
                    level.getChunk(x >> 4, z >> 4);
                    BlockSnapshot snapshot = runtime.liveWorld().read(position);
                    long bytes = 128L
                            + snapshot.blockState().getBytes(StandardCharsets.UTF_8).length
                            + snapshot.blockEntity().map(nbt -> nbt.bytes().length).orElse(0);
                    if (retainedBytes[0] + bytes > LUMI_MAX_CAPTURE_BYTES) {
                        available[0] = false;
                        before.clear();
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
        return available[0];
    }

    private static Map<SectionKey, Long> registerDurability(
            ServerLevel level,
            FabricDimensionRuntime runtime,
            Set<SectionKey> sections) {
        Map<SectionKey, Long> generations = new LinkedHashMap<>();
        for (SectionKey key : sections) {
            long generation = runtime.mutations().registerSectionMutation(key, () -> {
                try {
                    return LUMI_SECTIONS.capture(
                            level, level.getChunk(key.chunkX(), key.chunkZ()), key.sectionY());
                } catch (IOException failed) {
                    throw new UncheckedIOException("Cannot capture Axiom section origin", failed);
                }
            });
            generations.put(key, generation);
            runtime.blockEntityBaselines().discard(key);
        }
        return generations;
    }

    private static void recordAfter(
            ServerLevel level,
            FabricDimensionRuntime runtime,
            UUID action,
            Map<BlockPosition, BlockSnapshot> before,
            Map<SectionKey, Long> generations) {
        before.forEach((position, snapshot) -> {
            try {
                level.getChunk(position.x() >> 4, position.z() >> 4);
                BlockSnapshot after = runtime.liveWorld().read(position);
                if (!snapshot.equals(after)) {
                    runtime.liveBlocks().record(action, position, snapshot, after);
                    runtime.mutations().recordBuilderBlockMutation(
                            position, generations.get(section(position)));
                }
            } catch (IOException failed) {
                runtime.liveActions().makeUnavailable(action, "Cannot verify the Axiom edit after mutation");
                generations.keySet().forEach(runtime.mutations()::markBuilderMutation);
                throw new UncheckedIOException("Cannot capture Axiom block after mutation", failed);
            }
        });
    }

    private static Map<SectionKey, ObjectId> captureSectionIds(
            ServerLevel level,
            FabricDimensionRuntime runtime,
            Set<SectionKey> sections) {
        var objects = new WorldObjectRepository(runtime.repository());
        Map<SectionKey, ObjectId> before = new LinkedHashMap<>();
        for (SectionKey key : sections) {
            try {
                before.put(key, objects.identify(LUMI_SECTIONS.capture(
                        level, level.getChunk(key.chunkX(), key.chunkZ()), key.sectionY())));
            } catch (IOException failed) {
                throw new UncheckedIOException("Cannot identify Axiom section before mutation", failed);
            }
        }
        return before;
    }

    private static void recordFallback(
            ServerLevel level,
            FabricDimensionRuntime runtime,
            UUID action,
            Map<SectionKey, ObjectId> before) {
        var objects = new WorldObjectRepository(runtime.repository());
        boolean unavailable = false;
        for (SectionKey key : before.keySet()) {
            try {
                var visible = LUMI_SECTIONS.capture(
                        level, level.getChunk(key.chunkX(), key.chunkZ()), key.sectionY());
                if (!before.get(key).equals(objects.identify(visible))) {
                    if (!unavailable) {
                        runtime.liveActions().makeUnavailable(action, "Axiom edit exceeded its live capture limit");
                        unavailable = true;
                    }
                    runtime.mutations().markBuilderMutation(key);
                }
                runtime.liveBlocks().rebase(key, visible);
            } catch (IOException failed) {
                runtime.liveActions().makeUnavailable(action, "Cannot verify the Axiom edit after mutation");
                before.keySet().forEach(runtime.mutations()::markBuilderMutation);
                throw new UncheckedIOException("Cannot verify Axiom block entity state", failed);
            }
        }
    }

    private static SectionKey section(BlockPosition position) {
        return new SectionKey(
                Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.y(), 16),
                Math.floorDiv(position.z(), 16));
    }
}
