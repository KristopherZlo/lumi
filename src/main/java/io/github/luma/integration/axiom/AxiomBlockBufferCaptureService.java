package io.github.luma.integration.axiom;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.List;
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

        String actor = this.actorName(player);
        String actionId = "axiom-buffer-" + UUID.randomUUID();
        for (AxiomBlockMutation mutation : mutations) {
            this.recordMutation(level, mutation, actor, actionId);
        }
    }

    private void recordMutation(ServerLevel level, AxiomBlockMutation mutation, String actor, String actionId) {
        if (mutation == null || mutation.pos() == null || mutation.newState() == null) {
            return;
        }

        BlockPos pos = mutation.pos();
        BlockState oldState = level.getBlockState(pos);
        CompoundTag oldBlockEntity = this.blockEntityTag(level, pos);
        WorldMutationContext.pushExternalSource(WorldMutationSource.AXIOM, actor, actionId);
        try {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    level,
                    pos,
                    oldState,
                    mutation.newState(),
                    oldBlockEntity,
                    mutation.newBlockEntity()
            );
        } finally {
            WorldMutationContext.popSource();
        }
    }

    private CompoundTag blockEntityTag(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
    }

    private String actorName(ServerPlayer player) {
        if (player == null || player.getName() == null || player.getName().getString().isBlank()) {
            return "axiom";
        }
        return "axiom:" + player.getName().getString();
    }
}
