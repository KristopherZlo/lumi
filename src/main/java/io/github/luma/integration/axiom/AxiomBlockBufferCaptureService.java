package io.github.luma.integration.axiom;

import io.github.luma.LumaMod;
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

    public CaptureAttempt captureBeforeApply(Object blockBuffer, ServerLevel level, ServerPlayer player) {
        if (blockBuffer == null || level == null) {
            return CaptureAttempt.noBulkCapture("missing-context", 0, 0);
        }

        List<AxiomBlockMutation> mutations;
        try {
            mutations = this.extractor.extract(blockBuffer);
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("Failed to inspect Axiom block buffer before apply; direct section capture remains enabled", exception);
            return CaptureAttempt.failed("extractor-failed", 0, 0);
        }
        if (mutations.isEmpty()) {
            return CaptureAttempt.noBulkCapture("empty-buffer", 0, 0);
        }
        List<HistoryCaptureManager.BlockChangeInput> inputs;
        try {
            inputs = this.captureInputs(level, mutations);
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("Failed to prepare Axiom block buffer capture inputs; direct section capture remains enabled", exception);
            return CaptureAttempt.failed("input-capture-failed", mutations.size(), 0);
        }
        if (inputs.isEmpty()) {
            return CaptureAttempt.noBulkCapture("no-capturable-changes", mutations.size(), 0);
        }

        String actor = this.actorName(player);
        String actionId = "axiom-buffer-" + UUID.randomUUID();
        boolean accessAllowed = this.accessAllowed(level, player);
        try {
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
            return CaptureAttempt.captured(mutations.size(), inputs.size());
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("Failed to record Axiom block buffer before apply; direct section capture remains enabled", exception);
            return CaptureAttempt.failed("recording-failed", mutations.size(), inputs.size());
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

    public record CaptureAttempt(
            boolean captured,
            String reason,
            int extractedMutations,
            int capturedInputs
    ) {

        public boolean suppressDirectSectionFallback() {
            return this.captured;
        }

        static CaptureAttempt captured(int extractedMutations, int capturedInputs) {
            return new CaptureAttempt(true, "captured", extractedMutations, capturedInputs);
        }

        static CaptureAttempt noBulkCapture(String reason, int extractedMutations, int capturedInputs) {
            return new CaptureAttempt(false, reason, extractedMutations, capturedInputs);
        }

        static CaptureAttempt failed(String reason, int extractedMutations, int capturedInputs) {
            return new CaptureAttempt(false, reason, extractedMutations, capturedInputs);
        }
    }
}
