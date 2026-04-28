package io.github.luma.minecraft.capture;

import io.github.luma.integration.common.ExternalToolMutationDetector;
import io.github.luma.integration.common.ExternalToolMutationOriginDetector;
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
    private final ChunkSectionOwnerLookup ownershipRegistry;

    public static DirectSectionMutationCaptureService getInstance() {
        return INSTANCE;
    }

    private DirectSectionMutationCaptureService() {
        this(ExternalToolMutationOriginDetector.getInstance(), ChunkSectionOwnershipRegistry.getInstance());
    }

    DirectSectionMutationCaptureService(
            ExternalToolMutationDetector detector,
            ChunkSectionOwnerLookup ownershipRegistry
    ) {
        this.detector = detector;
        this.ownershipRegistry = ownershipRegistry;
    }

    public PendingDirectSectionMutation captureBefore(
            LevelChunkSection section,
            int localX,
            int localY,
            int localZ
    ) {
        if (WorldMutationCaptureGuard.suppressesDirectSectionCapture()
                || HistoryCaptureManager.shouldCaptureMutation(WorldMutationContext.currentSource())) {
            return PendingDirectSectionMutation.skipped();
        }

        var owner = this.ownershipRegistry.ownerOf(section);
        if (owner.isEmpty()) {
            return PendingDirectSectionMutation.skipped();
        }

        var operation = this.detector.detectOperation();
        if (operation.isEmpty()) {
            return PendingDirectSectionMutation.skipped();
        }

        ChunkSectionOwnershipRegistry.SectionOwner sectionOwner = owner.get();
        BlockPos pos = sectionOwner.blockPos(localX, localY, localZ);
        BlockState oldState = section.getBlockState(localX, localY, localZ);
        return new PendingDirectSectionMutation(
                pos,
                oldState,
                this.blockEntityTag(sectionOwner.level(), pos),
                operation.get()
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
                    section.getBlockState(localX, localY, localZ),
                    mutation.oldBlockEntity(),
                    this.blockEntityTag(level, mutation.pos())
            );
        } finally {
            WorldMutationContext.popSource();
        }
    }

    private CompoundTag blockEntityTag(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
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
