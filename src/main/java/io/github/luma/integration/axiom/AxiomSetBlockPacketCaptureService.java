package io.github.luma.integration.axiom;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.BlockEntitySnapshot;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.PostCallbackBlockMutationPolicy;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gives Axiom single block packets an explicit Lumi source frame.
 */
public final class AxiomSetBlockPacketCaptureService {

    private static final String ACTION_PREFIX = "axiom-set-block-";
    private static final int INFINITE_REACH_REASON = 64;
    private static final AxiomSetBlockPacketCaptureService INSTANCE =
            new AxiomSetBlockPacketCaptureService(UUID::randomUUID);

    private final Supplier<UUID> uuidSupplier;
    private final PostCallbackBlockMutationPolicy postCallbackMutationPolicy = new PostCallbackBlockMutationPolicy();

    public static AxiomSetBlockPacketCaptureService getInstance() {
        return INSTANCE;
    }

    AxiomSetBlockPacketCaptureService(Supplier<UUID> uuidSupplier) {
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
    }

    public WorldMutationContext.SourceFrame pushPacketSource(Object playerCandidate) {
        SourceIdentity identity = this.sourceIdentity(playerCandidate);
        return WorldMutationContext.pushExternalSource(
                identity.source(),
                identity.actor(),
                identity.actionId(),
                identity.accessAllowed()
        );
    }

    public PendingPacketCapture captureBefore(
            Object playerCandidate,
            Map<BlockPos, BlockState> requestedBlocks,
            int reason
    ) {
        if (!(playerCandidate instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)
                || !usesRequestedTransitionFallback(reason)
                || requestedBlocks == null
                || requestedBlocks.isEmpty()) {
            return PendingPacketCapture.empty();
        }
        try {
            List<PendingBlockMutation> mutations = new ArrayList<>(requestedBlocks.size());
            for (Map.Entry<BlockPos, BlockState> entry : requestedBlocks.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState requestedState = entry.getValue();
                if (pos == null || requestedState == null) {
                    continue;
                }
                BlockState oldState = level.getBlockState(pos);
                mutations.add(new PendingBlockMutation(
                        pos.immutable(),
                        oldState,
                        requestedState,
                        this.blockEntityTag(level, pos, oldState)
                ));
            }
            return new PendingPacketCapture(level, List.copyOf(mutations));
        } catch (RuntimeException exception) {
            LumaMod.LOGGER.warn("Failed to snapshot Axiom infinite-reach packet transitions", exception);
            return PendingPacketCapture.empty();
        }
    }

    static boolean usesRequestedTransitionFallback(int reason) {
        return (reason & INFINITE_REACH_REASON) != 0;
    }

    public void captureAfter(PendingPacketCapture capture) {
        if (capture == null || capture.level() == null || capture.mutations().isEmpty()) {
            return;
        }
        try {
            List<HistoryCaptureManager.BlockChangeInput> changes = new ArrayList<>(capture.mutations().size());
            for (PendingBlockMutation mutation : capture.mutations()) {
                BlockState appliedState = capture.level().getBlockState(mutation.pos());
                changes.addAll(this.postCallbackMutationPolicy.changesAfterCallbacks(
                        mutation.pos(),
                        mutation.oldState(),
                        mutation.requestedState(),
                        appliedState,
                        mutation.oldBlockEntity(),
                        this.blockEntityTag(capture.level(), mutation.pos(), appliedState)
                ));
            }
            HistoryCaptureManager.getInstance().recordBlockChanges(capture.level(), changes);
        } catch (RuntimeException exception) {
            LumaMod.LOGGER.warn("Failed to reconcile Axiom infinite-reach packet transitions", exception);
        }
    }

    SourceIdentity sourceIdentity(Object playerCandidate) {
        if (playerCandidate instanceof ServerPlayer player) {
            ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            return this.sourceIdentity(
                    player.getName() == null ? "" : player.getName().getString(),
                    level != null && level.getServer().isDedicatedServer(),
                    LumaAccessControl.getInstance().canUse(player)
            );
        }
        return this.sourceIdentity("", false, true);
    }

    SourceIdentity sourceIdentity(String playerName, boolean dedicatedServer, boolean playerAllowed) {
        return new SourceIdentity(
                WorldMutationSource.AXIOM,
                actorName(playerName),
                ACTION_PREFIX + this.nextActionSuffix(),
                !dedicatedServer || playerAllowed
        );
    }

    private String nextActionSuffix() {
        UUID uuid = this.uuidSupplier.get();
        return uuid == null ? UUID.randomUUID().toString() : uuid.toString();
    }

    private CompoundTag blockEntityTag(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return BlockEntitySnapshot.capture(level, blockEntity);
    }

    private static String actorName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return "axiom";
        }
        return "axiom:" + playerName;
    }

    record SourceIdentity(
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed
    ) {
    }

    public static final class PendingPacketCapture {

        private final ServerLevel level;
        private final List<PendingBlockMutation> mutations;

        private PendingPacketCapture(ServerLevel level, List<PendingBlockMutation> mutations) {
            this.level = level;
            this.mutations = mutations == null ? List.of() : List.copyOf(mutations);
        }

        private static PendingPacketCapture empty() {
            return new PendingPacketCapture(null, List.of());
        }

        private ServerLevel level() {
            return this.level;
        }

        private List<PendingBlockMutation> mutations() {
            return this.mutations;
        }
    }

    private record PendingBlockMutation(
            BlockPos pos,
            BlockState oldState,
            BlockState requestedState,
            CompoundTag oldBlockEntity
    ) {

        public PendingBlockMutation {
            pos = pos == null ? null : pos.immutable();
            oldBlockEntity = oldBlockEntity == null ? null : oldBlockEntity.copy();
        }
    }
}
