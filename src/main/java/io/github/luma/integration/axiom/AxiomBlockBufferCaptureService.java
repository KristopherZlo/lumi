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

    public PreparedCapture prepareBeforeApply(Object blockBuffer, ServerLevel level, ServerPlayer player) {
        if (blockBuffer == null || level == null) {
            return PreparedCapture.empty("missing-context", 0);
        }

        List<AxiomBlockMutation> mutations;
        try {
            mutations = this.extractor.extract(blockBuffer);
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("Failed to inspect Axiom block buffer before apply; direct section capture remains enabled", exception);
            return PreparedCapture.empty("extractor-failed", 0);
        }
        if (mutations.isEmpty()) {
            return PreparedCapture.empty("empty-buffer", 0);
        }
        List<HistoryCaptureManager.BlockChangeInput> inputs;
        try {
            inputs = this.captureInputs(level, mutations);
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("Failed to prepare Axiom block buffer capture inputs; direct section capture remains enabled", exception);
            return PreparedCapture.empty("input-capture-failed", mutations.size());
        }
        if (inputs.isEmpty()) {
            return PreparedCapture.empty("no-capturable-changes", mutations.size());
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
            return PreparedCapture.prepared(level, actor, actionId, accessAllowed, mutations.size(), inputs);
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("Failed to prepare Axiom block buffer capture; direct section capture remains enabled", exception);
            return PreparedCapture.empty("checkpoint-failed", mutations.size());
        }
    }

    public CaptureAttempt recordAfterApply(PreparedCapture preparedCapture) {
        if (preparedCapture == null) {
            return CaptureAttempt.noBulkCapture("not-prepared", 0, 0);
        }
        if (!preparedCapture.prepared()) {
            return CaptureAttempt.noBulkCapture(
                    preparedCapture.reason(),
                    preparedCapture.extractedMutations(),
                    preparedCapture.inputs().size()
            );
        }

        List<HistoryCaptureManager.BlockChangeInput> appliedInputs = this.appliedInputs(preparedCapture);
        if (appliedInputs.isEmpty()) {
            return CaptureAttempt.noBulkCapture(
                    "no-applied-changes",
                    preparedCapture.extractedMutations(),
                    0
            );
        }

        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                WorldMutationSource.AXIOM,
                preparedCapture.actor(),
                preparedCapture.actionId(),
                preparedCapture.accessAllowed()
        )) {
            HistoryCaptureManager.getInstance().recordBlockChanges(preparedCapture.level(), appliedInputs);
            return CaptureAttempt.captured(preparedCapture.extractedMutations(), appliedInputs.size());
        } catch (RuntimeException | LinkageError exception) {
            LumaMod.LOGGER.warn("Failed to record Axiom block buffer after apply; direct section capture remains enabled", exception);
            return CaptureAttempt.failed(
                    "recording-failed",
                    preparedCapture.extractedMutations(),
                    appliedInputs.size()
            );
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

    private List<HistoryCaptureManager.BlockChangeInput> appliedInputs(PreparedCapture preparedCapture) {
        List<HistoryCaptureManager.BlockChangeInput> applied = new ArrayList<>(preparedCapture.inputs().size());
        for (HistoryCaptureManager.BlockChangeInput input : preparedCapture.inputs()) {
            if (input == null || input.pos() == null || input.newState() == null) {
                continue;
            }
            BlockState liveState = preparedCapture.level().getBlockState(input.pos());
            if (!input.newState().equals(liveState)) {
                continue;
            }
            CompoundTag liveBlockEntity = this.blockEntityTag(preparedCapture.level(), input.pos(), liveState);
            if (input.newBlockEntity() != null && !Objects.equals(input.newBlockEntity(), liveBlockEntity)) {
                continue;
            }
            applied.add(new HistoryCaptureManager.BlockChangeInput(
                    input.pos(),
                    input.oldState(),
                    liveState,
                    input.oldBlockEntity(),
                    liveBlockEntity
            ));
        }
        return List.copyOf(applied);
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

    public record PreparedCapture(
            boolean prepared,
            String reason,
            ServerLevel level,
            String actor,
            String actionId,
            boolean accessAllowed,
            int extractedMutations,
            List<HistoryCaptureManager.BlockChangeInput> inputs
    ) {

        public boolean hasSourceContext() {
            return this.prepared && this.actionId != null && !this.actionId.isBlank();
        }

        static PreparedCapture prepared(
                ServerLevel level,
                String actor,
                String actionId,
                boolean accessAllowed,
                int extractedMutations,
                List<HistoryCaptureManager.BlockChangeInput> inputs
        ) {
            return new PreparedCapture(
                    true,
                    "prepared",
                    level,
                    actor,
                    actionId,
                    accessAllowed,
                    extractedMutations,
                    inputs
            );
        }

        static PreparedCapture empty(String reason, int extractedMutations) {
            return new PreparedCapture(
                    false,
                    reason,
                    null,
                    "",
                    "",
                    false,
                    extractedMutations,
                    List.of()
            );
        }

        public PreparedCapture {
            reason = reason == null ? "" : reason;
            actor = actor == null || actor.isBlank() ? "axiom" : actor;
            actionId = actionId == null ? "" : actionId;
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }

    public record CaptureAttempt(
            boolean captured,
            String reason,
            int extractedMutations,
            int capturedInputs
    ) {

        public boolean suppressDirectSectionFallback() {
            return false;
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
