package io.github.luma.integration.axiom;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.capture.AutoCheckpointService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class AxiomBlockBufferCaptureService {

    private static final AxiomBlockBufferCaptureService INSTANCE = new AxiomBlockBufferCaptureService();

    private final AxiomBlockBufferExtractor extractor;

    public static AxiomBlockBufferCaptureService getInstance() {
        return INSTANCE;
    }

    private AxiomBlockBufferCaptureService() {
        this(new AxiomBlockBufferExtractor());
    }

    AxiomBlockBufferCaptureService(AxiomBlockBufferExtractor extractor) {
        this.extractor = extractor;
    }

    public void captureBeforeApply(Object blockBuffer, ServerLevel level, ServerPlayer player) {
        if (blockBuffer == null || level == null) {
            return;
        }

        List<AxiomBlockMutation> mutations = this.extractor.extract(blockBuffer);
        if (mutations.isEmpty()) {
            return;
        }
        List<HistoryCaptureManager.BlockChangeInput> inputs = this.captureInputs(level, mutations);
        if (inputs.isEmpty()) {
            return;
        }

        String actor = this.actorName(player);
        String actionId = "axiom-buffer-" + UUID.randomUUID();
        boolean accessAllowed = this.accessAllowed(level, player);
        AutoCheckpointService.getInstance().checkpointBeforeExternalOperation(
                level,
                WorldMutationSource.AXIOM,
                actor,
                actionId,
                accessAllowed
        );
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                WorldMutationSource.AXIOM,
                actor,
                actionId,
                accessAllowed
        )) {
            HistoryCaptureManager.getInstance().recordBlockChanges(level, inputs);
        }
    }

    private List<HistoryCaptureManager.BlockChangeInput> captureInputs(
            ServerLevel level,
            List<AxiomBlockMutation> mutations
    ) {
        List<HistoryCaptureManager.BlockChangeInput> inputs = new ArrayList<>(mutations.size());
        for (AxiomBlockMutation mutation : mutations) {
            if (mutation == null || mutation.pos() == null || mutation.newState() == null) {
                continue;
            }

            BlockPos pos = mutation.pos();
            BlockState oldState = level.getBlockState(pos);
            CompoundTag oldBlockEntity = this.blockEntityTag(level, pos, oldState);
            if (this.sameStateWithoutBlockEntityChange(oldState, oldBlockEntity, mutation)) {
                continue;
            }
            inputs.add(new HistoryCaptureManager.BlockChangeInput(
                    pos,
                    oldState,
                    mutation.newState(),
                    oldBlockEntity,
                    mutation.newBlockEntity()
            ));
        }
        return List.copyOf(inputs);
    }

    private boolean sameStateWithoutBlockEntityChange(
            BlockState oldState,
            CompoundTag oldBlockEntity,
            AxiomBlockMutation mutation
    ) {
        if (oldState == null || mutation == null || !oldState.equals(mutation.newState())) {
            return false;
        }
        return mutation.newBlockEntity() == null
                || Objects.equals(oldBlockEntity, mutation.newBlockEntity());
    }

    private CompoundTag blockEntityTag(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
    }

    private String actorName(ServerPlayer player) {
        if (player == null || player.getName() == null || player.getName().getString().isBlank()) {
            return "axiom";
        }
        return "axiom:" + player.getName().getString();
    }

    private boolean accessAllowed(ServerLevel level, ServerPlayer player) {
        if (level == null || !level.getServer().isDedicatedServer()) {
            return true;
        }
        return LumaAccessControl.getInstance().canUse(player);
    }
}
