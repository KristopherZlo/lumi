package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.PendingRestoreCompletion;
import io.github.luma.domain.model.PlayerRespawnPoint;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PlayerRespawnRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;

/**
 * Publishes restore metadata after prepared world apply has succeeded.
 */
final class RestoreCompletionCoordinator {

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final PlayerRespawnRepository playerRespawnRepository = new PlayerRespawnRepository();
    private final PartialRestoreDraftRewriter partialRestoreDraftRewriter = new PartialRestoreDraftRewriter();

    void completePartialRestore(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft pendingDraft,
            PartialRestoreRequest request,
            RecoveryDraft partialDraft,
            int batchCount
    ) throws IOException {
        Instant now = Instant.now();
        RecoveryDraft mergedDraft = this.partialRestoreDraftRewriter.mergeRestoredChanges(
                pendingDraft,
                partialDraft,
                now
        );
        RecoveryDraft durableOperationDraft = mergedDraft == null
                ? this.partialRestoreDraftRewriter.emptyRestoredDraft(pendingDraft, partialDraft, now)
                : mergedDraft;
        this.recoveryRepository.saveOperationDraft(layout, durableOperationDraft);
        this.recoveryRepository.savePendingRestoreCompletion(layout, PendingRestoreCompletion.partial(
                project.id().toString(),
                partialDraft.variantId(),
                request.targetVersionId(),
                now,
                request.bounds(),
                request.restoreMode()
        ));
        this.partialRestoreDraftRewriter.saveDraftOrDelete(layout, mergedDraft);
        if (mergedDraft != null) {
            HistoryCaptureManager.getInstance().markPersistedDraftCurrentRun(level.getServer(), project.id().toString());
        }
        this.recoveryRepository.deleteOperationDraft(layout);
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "partial-restore-completed",
                "Partial restore applied target state as pending draft changes",
                request.targetVersionId(),
                partialDraft.variantId()
        ));
        this.recoveryRepository.deletePendingRestoreCompletion(layout);
        UndoRedoHistoryManager.getInstance().clearProject(project.id().toString());
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        LumaMod.LOGGER.info(
                "Completed partial restore for project {} to version {} with {} chunk batches and {} changes",
                project.name(),
                request.targetVersionId(),
                batchCount,
                partialDraft.totalChangeCount()
        );
    }

    void completeRestore(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVariant> variants,
            ProjectVariant targetVariant,
            ProjectVersion version,
            int batchCount
    ) throws IOException {
        Instant now = Instant.now();
        this.recoveryRepository.savePendingRestoreCompletion(layout, PendingRestoreCompletion.full(
                project.id().toString(),
                targetVariant.id(),
                version.id(),
                now
        ));
        List<ProjectVariant> latestVariants = this.variantRepository.loadAll(layout);
        this.variantRepository.save(layout, this.replaceVariantHead(
                latestVariants.isEmpty() ? variants : latestVariants,
                targetVariant.id(),
                version.id()
        ));
        BuildProject updatedProject = targetVariant.id().equals(project.activeVariantId())
                ? project.withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(now)
                : project.withActiveVariantId(targetVariant.id(), now)
                        .withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION);
        this.projectRepository.save(layout, updatedProject);
        this.recoveryRepository.deleteDraft(layout);
        UndoRedoHistoryManager.getInstance().clearProject(project.id().toString());
        this.restorePlayerRespawns(level, layout, version);
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "restore-completed",
                "Restored project state and reset branch head to version " + version.id(),
                version.id(),
                targetVariant.id()
        ));
        this.recoveryRepository.deletePendingRestoreCompletion(layout);
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        LumaMod.LOGGER.info(
                "Completed restore for project {} to version {} on variant {} with {} prepared chunk batches",
                project.name(),
                version.id(),
                targetVariant.id(),
                batchCount
        );
    }

    private void restorePlayerRespawns(ServerLevel level, ProjectLayout layout, ProjectVersion version) throws IOException {
        if (level == null || level.getServer() == null || version == null) {
            return;
        }
        List<PlayerRespawnPoint> points = this.playerRespawnRepository.loadVersion(layout, version.id());
        if (points.isEmpty()) {
            return;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            this.matchRespawn(points, player).ifPresent(point -> this.restorePlayerRespawn(level, player, point));
        }
    }

    private Optional<PlayerRespawnPoint> matchRespawn(List<PlayerRespawnPoint> points, ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        String uuid = player.getUUID().toString();
        String name = player.getName().getString();
        return points.stream()
                .filter(point -> uuid.equals(point.playerUuid()) || name.equals(point.playerName()))
                .findFirst();
    }

    private void restorePlayerRespawn(ServerLevel currentLevel, ServerPlayer player, PlayerRespawnPoint point) {
        ResourceKey<Level> dimension = this.dimensionKey(point.dimensionId());
        ServerLevel spawnLevel = currentLevel.getServer().getLevel(dimension);
        if (spawnLevel == null) {
            return;
        }
        BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
        if (!point.forced() && !this.isRespawnAnchorBlock(spawnLevel.getBlockState(pos))) {
            return;
        }
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                LevelData.RespawnData.of(dimension, pos, point.yaw(), point.pitch()),
                point.forced()
        ), false);
    }

    private boolean isRespawnAnchorBlock(BlockState state) {
        return state != null && (state.getBlock() instanceof BedBlock || state.is(Blocks.RESPAWN_ANCHOR));
    }

    private ResourceKey<Level> dimensionKey(String dimensionId) {
        Identifier identifier = Identifier.tryParse(dimensionId);
        if (identifier == null) {
            identifier = Level.OVERWORLD.identifier();
        }
        return ResourceKey.create(Registries.DIMENSION, identifier);
    }

    private List<ProjectVariant> replaceVariantHead(
            List<ProjectVariant> variants,
            String targetVariantId,
            String targetVersionId
    ) {
        List<ProjectVariant> updated = new ArrayList<>();
        for (ProjectVariant variant : variants) {
            if (!variant.id().equals(targetVariantId)) {
                updated.add(variant);
                continue;
            }
            updated.add(new ProjectVariant(
                    variant.id(),
                    variant.name(),
                    variant.baseVersionId(),
                    targetVersionId,
                    variant.main(),
                    variant.createdAt(),
                    variant.switchKey()
            ));
        }
        return updated;
    }
}
