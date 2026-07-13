package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.integration.common.ExternalToolMutationDetector;
import io.github.luma.integration.common.ExternalToolMutationOriginDetector;
import io.github.luma.integration.common.ExternalToolMutationSourceResolver;
import io.github.luma.integration.common.ObservedExternalToolOperation;
import io.github.luma.minecraft.world.WorldOperationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class DirectSectionMutationCaptureService {

    private static final DirectSectionMutationCaptureService INSTANCE = new DirectSectionMutationCaptureService();

    private final ExternalToolMutationDetector detector;
    private final ExternalToolMutationSourceResolver sourceResolver;
    private final ChunkSectionOwnerLookup ownershipRegistry;

    public static DirectSectionMutationCaptureService getInstance() {
        return INSTANCE;
    }

    private DirectSectionMutationCaptureService() {
        this(
                ExternalToolMutationOriginDetector.getInstance(),
                ExternalToolMutationSourceResolver.getInstance(),
                ChunkSectionOwnershipRegistry.getInstance()
        );
    }

    DirectSectionMutationCaptureService(
            ExternalToolMutationDetector detector,
            ChunkSectionOwnerLookup ownershipRegistry
    ) {
        this(detector, ExternalToolMutationSourceResolver.getInstance(), ownershipRegistry);
    }

    DirectSectionMutationCaptureService(
            ExternalToolMutationDetector detector,
            ExternalToolMutationSourceResolver sourceResolver,
            ChunkSectionOwnerLookup ownershipRegistry
    ) {
        this.detector = detector;
        this.sourceResolver = sourceResolver;
        this.ownershipRegistry = ownershipRegistry;
    }

    public boolean requiresInterception() {
        WorldMutationSource source = WorldMutationContext.currentSource();
        if (source == WorldMutationSource.RESTORE) {
            return false;
        }
        if (WorldOperationManager.getInstance().mayBlockWorldMutations()) {
            return !WorldMutationContext.captureSuppressed();
        }
        if (!HistoryCaptureManager.shouldCaptureMutation(source) && !this.detector.detectionAvailable()) {
            return false;
        }
        return !WorldMutationContext.captureSuppressed()
                && !WorldMutationCaptureGuard.suppressesDirectSectionCapture();
    }

    public PendingDirectSectionMutation captureBefore(
            LevelChunkSection section,
            int localX,
            int localY,
            int localZ,
            BlockState newState
    ) {
        if (WorldMutationContext.captureSuppressed()) {
            return PendingDirectSectionMutation.skipped();
        }
        if (section == null) {
            return PendingDirectSectionMutation.skipped();
        }

        var owner = this.ownershipRegistry.ownerOf(section);
        ChunkSectionOwnershipRegistry.SectionOwner sectionOwner = owner.orElse(null);
        if (sectionOwner != null
                && WorldOperationManager.getInstance().blocksWorldMutations(sectionOwner.level())) {
            return PendingDirectSectionMutation.blocked(
                    sectionOwner,
                    section.getBlockState(localX, localY, localZ)
            );
        }
        if (WorldMutationCaptureGuard.suppressesDirectSectionCapture()) {
            return PendingDirectSectionMutation.skipped();
        }
        BlockPos pos = sectionOwner == null ? null : sectionOwner.blockPos(localX, localY, localZ);
        BlockState oldState = section.getBlockState(localX, localY, localZ);
        ServerLevel ownerLevel = sectionOwner == null ? null : sectionOwner.level();
        CompoundTag oldBlockEntity = this.blockEntityTag(ownerLevel, pos, oldState);
        boolean captureCurrentSource = this.currentSourceCaptures();
        ObservedExternalToolOperation operation = this.detectOperation(captureCurrentSource);
        if (ownerLevel != null && !oldState.equals(newState)) {
            this.capturePreMutationBaseline(
                    ownerLevel,
                    pos,
                    oldState,
                    oldBlockEntity,
                    operation,
                    captureCurrentSource
            );
        }
        if (operation == null && !captureCurrentSource) {
            return PendingDirectSectionMutation.skipped();
        }
        return new PendingDirectSectionMutation(
                sectionOwner,
                pos,
                oldState,
                oldBlockEntity,
                operation,
                operation == null,
                false
        );
    }

    public void captureAfter(
            LevelChunkSection section,
            int localX,
            int localY,
            int localZ,
            PendingDirectSectionMutation mutation
    ) {
        if (section == null || mutation == null || !mutation.shouldCapture()) {
            return;
        }

        ChunkSectionOwnershipRegistry.SectionOwner owner = mutation.owner();
        if (owner == null) {
            return;
        }

        ServerLevel level = owner.level();
        BlockPos pos = mutation.pos() == null ? owner.blockPos(localX, localY, localZ) : mutation.pos();
        BlockState appliedState = section.getBlockState(localX, localY, localZ);
        if (mutation.currentSource()) {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    level,
                    pos,
                    mutation.oldState(),
                    appliedState,
                    mutation.oldBlockEntity(),
                    this.blockEntityTag(level, pos, appliedState)
            );
            return;
        }

        boolean accessAllowed = this.accessAllowed(level, mutation.operation());
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                mutation.operation().source(),
                mutation.operation().actor(),
                mutation.operation().actionId(),
                accessAllowed
        )) {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    level,
                    pos,
                    mutation.oldState(),
                    appliedState,
                    mutation.oldBlockEntity(),
                    this.blockEntityTag(level, pos, appliedState)
            );
        }
    }

    private CompoundTag blockEntityTag(ServerLevel level, BlockPos pos, BlockState state) {
        if (level == null || state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return BlockEntitySnapshot.capture(level, blockEntity);
    }

    private boolean currentSourceCaptures() {
        return HistoryCaptureManager.shouldCaptureMutation(WorldMutationContext.currentSource());
    }

    private ObservedExternalToolOperation detectOperation(boolean currentSourceCaptures) {
        var currentSource = WorldMutationContext.currentSource();
        boolean captureSuppressed = WorldMutationContext.captureSuppressed();
        if (currentSourceCaptures) {
            return this.sourceResolver.detectPlayerSourceOverride(currentSource, captureSuppressed)
                    .map(operation -> operation.withAccessAllowed(WorldMutationContext.currentAccessAllowed()))
                    .orElse(null);
        }
        if (captureSuppressed) {
            return null;
        }
        return this.detector.detectOperation().orElse(null);
    }

    private boolean accessAllowed(ServerLevel level, ObservedExternalToolOperation operation) {
        return operation != null
                && (operation.accessAllowed() || level == null || !level.getServer().isDedicatedServer());
    }

    private void capturePreMutationBaseline(
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            ObservedExternalToolOperation operation,
            boolean currentSourceCaptures
    ) {
        if (operation == null || currentSourceCaptures) {
            HistoryCaptureManager.getInstance().capturePreMutationBaseline(level, pos, oldState, oldBlockEntity);
            return;
        }
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                operation.source(), operation.actor(), operation.actionId(), operation.accessAllowed()
        )) {
            HistoryCaptureManager.getInstance().capturePreMutationBaseline(level, pos, oldState, oldBlockEntity);
        }
    }

    public record PendingDirectSectionMutation(
            ChunkSectionOwnershipRegistry.SectionOwner owner,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            ObservedExternalToolOperation operation,
            boolean currentSource,
            boolean blocked
    ) {

        private static PendingDirectSectionMutation skipped() {
            return new PendingDirectSectionMutation(null, null, null, null, null, false, false);
        }

        private static PendingDirectSectionMutation blocked(
                ChunkSectionOwnershipRegistry.SectionOwner owner,
                BlockState oldState
        ) {
            return new PendingDirectSectionMutation(owner, null, oldState, null, null, false, true);
        }

        private boolean shouldCapture() {
            return this.operation != null || this.currentSource;
        }
    }
}
