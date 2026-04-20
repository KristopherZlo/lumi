package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.SnapshotReader;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Restores a project to a target version through checkpoint snapshots and patch
 * replay.
 *
 * <p>The service builds a restore plan off-thread, decodes the required
 * snapshot, patch, and baseline payloads into prepared chunk batches, and hands
 * those batches to {@link WorldOperationManager} for bounded tick-thread
 * application.
 */
public final class RestoreService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final SnapshotReader snapshotReader = new SnapshotReader();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final VersionService versionService = new VersionService();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    /**
     * Starts a restore operation for the given project and target version.
     *
     * <p>If configured, the current pending draft is first saved as a restore
     * checkpoint so the player can return to the pre-restore state.
     */
    public OperationHandle restore(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));

        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion version = this.resolveVersion(project, versions, variants, versionId);
        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        RecoveryDraft pendingDraft = HistoryCaptureManager.getInstance()
                .freezeSession(level.getServer(), project.id().toString())
                .map(TrackedChangeBuffer::toDraft)
                .or(() -> persistedDraft)
                .orElse(null);
        LumaMod.LOGGER.info(
                "Starting restore request for project {} to version {} on variant {}",
                project.name(),
                version.id(),
                version.variantId()
        );

        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "restore-started",
                "Started restore to version " + version.id(),
                version.id(),
                version.variantId()
        ));

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "restore-version",
                "blocks",
                progressSink -> {
                    if (project.settings().safetySnapshotBeforeRestore() && pendingDraft != null && !pendingDraft.isEmpty()) {
                        LumaMod.LOGGER.info(
                                "Creating safety checkpoint before restore for project {} with {} pending changes",
                                project.name(),
                                pendingDraft.changes().size()
                        );
                        progressSink.update(OperationStage.WRITING, 0, pendingDraft.changes().size(), "Writing restore checkpoint");
                        this.versionService.writeVersion(
                                level,
                                layout,
                                project,
                                pendingDraft,
                                "",
                                "Luma",
                                VersionKind.RESTORE,
                                false,
                                progressSink
                        );
                    }

                    Optional<List<PreparedChunkBatch>> prepared = version.versionKind() == VersionKind.WORLD_ROOT
                            ? Optional.of(this.decodeWorldRootRestore(layout, project, level, progressSink))
                            : this.tryDecodeDirectRestore(
                                    layout,
                                    project,
                                    versions,
                                    variants,
                                    version,
                                    pendingDraft,
                                    level,
                                    progressSink
                            );

                    List<PreparedChunkBatch> batches = prepared.orElseGet(() -> {
                                try {
                                    progressSink.update(OperationStage.PREPARING, 0, 1, "Planning restore");
                                    RestorePlan plan = this.buildPlan(layout, project, versions, version);
                                    LumaMod.LOGGER.info(
                                            "Restore plan for project {} uses anchor={}, patches={}, baselineGaps={}",
                                            project.name(),
                                            plan.anchor().id(),
                                            plan.patchChain().size(),
                                            plan.baselineGaps().size()
                                    );
                                    return this.decodePlan(layout, level, plan, progressSink);
                                } catch (IOException exception) {
                                    throw new RuntimeException(exception);
                                }
                            });
                    return new WorldOperationManager.PreparedApplyOperation(
                            batches,
                            () -> {
                                this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                                        Instant.now(),
                                        "restore-completed",
                                        "Restored project state to version " + version.id(),
                                        version.id(),
                                        version.variantId()
                                ));
                                HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
                                LumaMod.LOGGER.info(
                                        "Completed restore for project {} to version {} with {} prepared chunk batches",
                                        project.name(),
                                        version.id(),
                                        batches.size()
                                );
                            }
                    );
                }
        );
    }

    private List<PreparedChunkBatch> decodeWorldRootRestore(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            ServerLevel level,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        List<ChunkPoint> trackedChunks = this.baselineChunkRepository.listChunks(layout);
        if (trackedChunks.isEmpty()) {
            LumaMod.LOGGER.info("World root restore for project {} has no tracked baseline chunks yet", project.name());
            return List.of();
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        int index = 0;
        for (ChunkPoint chunk : trackedChunks) {
            batches.addAll(this.baselineChunkRepository.decodeBatches(layout, chunk, level));
            index += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    index,
                    trackedChunks.size(),
                    "Decoded world root chunk " + chunk.x() + ":" + chunk.z()
            );
        }

        List<PreparedChunkBatch> collapsed = collapsePreparedBatches(batches);
        LumaMod.LOGGER.info(
                "Decoded {} tracked baseline chunks for world root restore in project {}",
                trackedChunks.size(),
                project.name()
        );
        return collapsed;
    }

    private ProjectVersion resolveVersion(
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            String versionId
    ) {
        if (versionId != null && !versionId.isBlank()) {
            return versions.stream()
                    .filter(candidate -> candidate.id().equals(versionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        }

        String activeVariantId = project.activeVariantId();
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(activeVariantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name()));

        return versions.stream()
                .filter(candidate -> candidate.id().equals(activeVariant.headVersionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant head version is missing: " + activeVariant.headVersionId()));
    }

    private Optional<List<PreparedChunkBatch>> tryDecodeDirectRestore(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            ServerLevel level,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElse(null);
        if (activeVariant == null || activeVariant.headVersionId() == null || activeVariant.headVersionId().isBlank()) {
            return Optional.empty();
        }
        if (!targetVersion.variantId().equals(activeVariant.id())) {
            return Optional.empty();
        }

        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

        String headVersionId = activeVariant.headVersionId();
        List<ProjectVersion> directVersions = new ArrayList<>();
        boolean applyNewValues;

        if (targetVersion.id().equals(headVersionId)) {
            applyNewValues = false;
        } else if (this.isAncestor(versionMap, targetVersion.id(), headVersionId)) {
            applyNewValues = false;
            ProjectVersion cursor = versionMap.get(headVersionId);
            while (cursor != null && !cursor.id().equals(targetVersion.id())) {
                directVersions.add(cursor);
                cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                        ? null
                        : versionMap.get(cursor.parentVersionId());
            }
            if (cursor == null) {
                return Optional.empty();
            }
        } else if (this.isAncestor(versionMap, headVersionId, targetVersion.id())) {
            applyNewValues = true;
            List<ProjectVersion> reversed = new ArrayList<>();
            ProjectVersion cursor = targetVersion;
            while (cursor != null && !cursor.id().equals(headVersionId)) {
                reversed.add(cursor);
                cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                        ? null
                        : versionMap.get(cursor.parentVersionId());
            }
            if (cursor == null) {
                return Optional.empty();
            }
            for (int index = reversed.size() - 1; index >= 0; index--) {
                directVersions.add(reversed.get(index));
            }
        } else {
            return Optional.empty();
        }

        int totalSources = directVersions.size() + (pendingDraft != null && !pendingDraft.isEmpty() ? 1 : 0);
        int completedSources = 0;
        List<PreparedChunkBatch> batches = new ArrayList<>();

        if (pendingDraft != null && !pendingDraft.isEmpty()) {
            batches.addAll(this.decodeStoredChanges(level, pendingDraft.changes(), false));
            completedSources += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    completedSources,
                    Math.max(1, totalSources),
                    "Decoded pending draft rollback"
            );
        }

        for (ProjectVersion version : directVersions) {
            batches.addAll(this.decodeVersionChanges(layout, level, version, applyNewValues));
            completedSources += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    completedSources,
                    Math.max(1, totalSources),
                    applyNewValues
                            ? "Decoded forward patch " + version.id()
                            : "Decoded reverse patch " + version.id()
            );
        }

        List<PreparedChunkBatch> collapsed = collapsePreparedBatches(batches);
        int rawPlacements = totalPlacements(batches);
        int collapsedPlacements = totalPlacements(collapsed);
        LumaMod.LOGGER.info(
                "Using direct {} restore for project {} from head {} to target {} with {} patch steps, draftRollback={}, placements {} -> {}",
                applyNewValues ? "forward" : "reverse",
                project.name(),
                headVersionId,
                targetVersion.id(),
                directVersions.size(),
                pendingDraft != null && !pendingDraft.isEmpty(),
                rawPlacements,
                collapsedPlacements
        );
        return Optional.of(collapsed);
    }

    private RestorePlan buildPlan(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion
    ) throws IOException {
        RestoreChain chain = this.resolveChain(versions, targetVersion);
        List<ChunkPointAccumulator> restoredChunks = new ArrayList<>();

        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            for (var chunk : this.snapshotReader.loadChunks(layout.snapshotFile(chain.anchor().snapshotId()))) {
                restoredChunks.add(new ChunkPointAccumulator(chunk.x(), chunk.z()));
            }
        }

        List<PatchMetadata> patchMetadata = new ArrayList<>();
        for (ProjectVersion patchVersion : chain.patchVersions()) {
            for (String patchId : patchVersion.patchIds()) {
                PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                patchMetadata.add(metadata);
                for (var chunk : metadata.chunks()) {
                    restoredChunks.add(new ChunkPointAccumulator(chunk.chunkX(), chunk.chunkZ()));
                }
            }
        }

        List<io.github.luma.domain.model.ChunkPoint> dedupedChunks = new ArrayList<>();
        Map<String, io.github.luma.domain.model.ChunkPoint> deduped = new LinkedHashMap<>();
        for (ChunkPointAccumulator chunk : restoredChunks) {
            deduped.put(chunk.chunkX + ":" + chunk.chunkZ, new io.github.luma.domain.model.ChunkPoint(chunk.chunkX, chunk.chunkZ));
        }
        dedupedChunks.addAll(deduped.values());

        List<io.github.luma.domain.model.ChunkPoint> baselineGaps = project.tracksWholeDimension()
                ? this.baselineChunkRepository.listMissingChunks(layout, dedupedChunks)
                : List.of();

        return new RestorePlan(chain.anchor(), patchMetadata, baselineGaps);
    }

    private RestoreChain resolveChain(List<ProjectVersion> versions, ProjectVersion targetVersion) {
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

        List<ProjectVersion> patchVersions = new ArrayList<>();
        ProjectVersion cursor = targetVersion;
        while (cursor != null && (cursor.snapshotId() == null || cursor.snapshotId().isBlank())) {
            patchVersions.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }

        if (cursor == null) {
            throw new IllegalArgumentException("No checkpoint snapshot found for version " + targetVersion.id());
        }

        patchVersions.sort(Comparator.comparing(ProjectVersion::createdAt));
        LumaMod.LOGGER.info(
                "Resolved restore chain for version {} with anchor {} and {} patch versions",
                targetVersion.id(),
                cursor.id(),
                patchVersions.size()
        );
        return new RestoreChain(cursor, patchVersions);
    }

    private List<PreparedChunkBatch> decodePlan(
            ProjectLayout layout,
            ServerLevel level,
            RestorePlan plan,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        int totalSources = plan.baselineGaps().size()
                + (plan.anchor().snapshotId() == null || plan.anchor().snapshotId().isBlank() ? 0 : 1)
                + plan.patchChain().size();
        int completedSources = 0;
        List<PreparedChunkBatch> batches = new ArrayList<>();

        for (io.github.luma.domain.model.ChunkPoint chunk : plan.baselineGaps()) {
            batches.addAll(this.baselineChunkRepository.decodeBatches(layout, chunk, level));
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded baseline chunk " + chunk.x() + ":" + chunk.z());
        }

        if (plan.anchor().snapshotId() != null && !plan.anchor().snapshotId().isBlank()) {
            batches.addAll(this.snapshotReader.decodeBatches(layout.snapshotFile(plan.anchor().snapshotId()), level));
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded anchor snapshot");
        }

        for (PatchMetadata patch : plan.patchChain()) {
            batches.addAll(this.patchDataRepository.decodeBatches(layout, patch, level));
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded patch " + patch.id());
        }

        List<PreparedChunkBatch> collapsed = collapsePreparedBatches(batches);
        int rawPlacements = totalPlacements(batches);
        int collapsedPlacements = totalPlacements(collapsed);
        if (rawPlacements != collapsedPlacements) {
            LumaMod.LOGGER.info(
                    "Collapsed restore placements from {} to {} after combining snapshot, baseline, and patch batches",
                    rawPlacements,
                    collapsedPlacements
            );
        }
        return collapsed;
    }

    private List<PreparedChunkBatch> decodeVersionChanges(
            ProjectLayout layout,
            ServerLevel level,
            ProjectVersion version,
            boolean applyNewValues
    ) throws IOException {
        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (String patchId : version.patchIds()) {
            PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                    .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
            batches.addAll(this.decodeStoredChanges(level, this.patchDataRepository.loadChanges(layout, metadata), applyNewValues));
        }
        return batches;
    }

    private List<PreparedChunkBatch> decodeStoredChanges(
            ServerLevel level,
            List<StoredBlockChange> changes,
            boolean applyNewValues
    ) throws IOException {
        Map<ChunkPoint, List<PreparedBlockPlacement>> grouped = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            StatePayload target = applyNewValues ? change.newValue() : change.oldValue();
            BlockPos pos = new BlockPos(change.pos().x(), change.pos().y(), change.pos().z());
            ChunkPoint chunk = new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4);
            grouped.computeIfAbsent(chunk, ignored -> new ArrayList<>())
                    .add(new PreparedBlockPlacement(
                            pos,
                            io.github.luma.minecraft.world.BlockStateNbtCodec.deserializeBlockState(level, target == null ? null : target.stateTag()),
                            target == null || target.blockEntityTag() == null ? null : target.blockEntityTag().copy()
                    ));
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (Map.Entry<ChunkPoint, List<PreparedBlockPlacement>> entry : grouped.entrySet()) {
            batches.add(new PreparedChunkBatch(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return batches;
    }

    private boolean isAncestor(Map<String, ProjectVersion> versionMap, String ancestorVersionId, String descendantVersionId) {
        ProjectVersion cursor = versionMap.get(descendantVersionId);
        while (cursor != null) {
            if (cursor.id().equals(ancestorVersionId)) {
                return true;
            }
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }
        return false;
    }

    static List<PreparedChunkBatch> collapsePreparedBatches(List<PreparedChunkBatch> batches) {
        Map<ChunkPoint, LinkedHashMap<Long, PreparedBlockPlacement>> collapsed = new LinkedHashMap<>();
        for (PreparedChunkBatch batch : batches) {
            if (batch == null || batch.placements().isEmpty()) {
                continue;
            }
            LinkedHashMap<Long, PreparedBlockPlacement> chunkPlacements = collapsed.computeIfAbsent(
                    batch.chunk(),
                    ignored -> new LinkedHashMap<>()
            );
            for (PreparedBlockPlacement placement : batch.placements()) {
                long packedPos = BlockPos.asLong(placement.pos().getX(), placement.pos().getY(), placement.pos().getZ());
                chunkPlacements.put(packedPos, placement);
            }
        }

        List<PreparedChunkBatch> result = new ArrayList<>();
        for (Map.Entry<ChunkPoint, LinkedHashMap<Long, PreparedBlockPlacement>> entry : collapsed.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.add(new PreparedChunkBatch(entry.getKey(), List.copyOf(entry.getValue().values())));
            }
        }
        return result;
    }

    private static int totalPlacements(List<PreparedChunkBatch> batches) {
        int total = 0;
        for (PreparedChunkBatch batch : batches) {
            total += batch.placements().size();
        }
        return total;
    }

    private record RestoreChain(ProjectVersion anchor, List<ProjectVersion> patchVersions) {
    }

    private record RestorePlan(
            ProjectVersion anchor,
            List<PatchMetadata> patchChain,
            List<io.github.luma.domain.model.ChunkPoint> baselineGaps
    ) {
    }

    private record ChunkPointAccumulator(int chunkX, int chunkZ) {
    }
}
