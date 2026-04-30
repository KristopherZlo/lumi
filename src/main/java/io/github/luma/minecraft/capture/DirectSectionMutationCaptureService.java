package io.github.luma.minecraft.capture;

import io.github.luma.integration.common.ExternalToolMutationDetector;
import io.github.luma.integration.common.ExternalToolMutationOriginDetector;
import io.github.luma.integration.common.ExternalToolMutationSourceResolver;
import io.github.luma.integration.common.ObservedExternalToolOperation;
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

    public PendingDirectSectionMutation captureBefore(
            LevelChunkSection section,
            int localX,
            int localY,
            int localZ
    ) {
        if (WorldMutationCaptureGuard.suppressesDirectSectionCapture()) {
            return PendingDirectSectionMutation.skipped();
        }

        var owner = this.ownershipRegistry.ownerOf(section);
        if (owner.isEmpty()) {
            return PendingDirectSectionMutation.skipped();
        }

        ObservedExternalToolOperation operation = this.detectOperation();
        if (operation == null) {
            return PendingDirectSectionMutation.skipped();
        }

        ChunkSectionOwnershipRegistry.SectionOwner sectionOwner = owner.get();
        BlockPos pos = sectionOwner.blockPos(localX, localY, localZ);
        BlockState oldState = section.getBlockState(localX, localY, localZ);
        return new PendingDirectSectionMutation(
                pos,
                oldState,
                this.blockEntityTag(sectionOwner.level(), pos, oldState),
                operation
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

        var owner = this.ownershipRegistry.ownerOf(section);
        if (owner.isEmpty()) {
            return;
        }

        ServerLevel level = owner.get().level();
        BlockState appliedState = section.getBlockState(localX, localY, localZ);
        WorldMutationContext.pushExternalSource(
                mutation.operation().source(),
                mutation.operation().actor(),
                mutation.operation().actionId()
        );
        try {
            HistoryCaptureManager.getInstance().recordBlockChange(
                    level,
                    mutation.pos(),
                    mutation.oldState(),
                    appliedState,
                    mutation.oldBlockEntity(),
                    this.blockEntityTag(level, mutation.pos(), appliedState)
            );
        } finally {
            WorldMutationContext.popSource();
        }
    }

    private CompoundTag blockEntityTag(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
    }

    private ObservedExternalToolOperation detectOperation() {
        var currentSource = WorldMutationContext.currentSource();
        boolean captureSuppressed = WorldMutationContext.captureSuppressed();
        if (HistoryCaptureManager.shouldCaptureMutation(currentSource)) {
            return this.sourceResolver.detectPlayerSourceOverride(currentSource, captureSuppressed).orElse(null);
        }
        if (captureSuppressed) {
            return null;
        }
        return this.detector.detectOperation().orElse(null);
    }

    public record PendingDirectSectionMutation(
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            ObservedExternalToolOperation operation
    ) {

        private static PendingDirectSectionMutation skipped() {
            return new PendingDirectSectionMutation(null, null, null, null);
        }

        private boolean shouldCapture() {
            return this.operation != null;
        }
    }
}
