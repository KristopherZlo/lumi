package io.github.luma.domain.service;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.HistoryPackageSafetyReport;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VariantMergeApplyRequest;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.EntityApplyMode;
import io.github.luma.minecraft.world.PreparedApplyOperation;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedChunkBatchCollapser;
import io.github.luma.minecraft.world.WorldChangeBatchPreparer;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class VariantMergeService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final VersionService versionService = new VersionService();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final WorldChangeBatchPreparer batchPreparer = new WorldChangeBatchPreparer();
    private final PreparedChunkBatchCollapser batchCollapser = new PreparedChunkBatchCollapser();
    private final VersionLineageService lineageService = new VersionLineageService();
    private final HistoryPackageSafetyScanner safetyScanner = new HistoryPackageSafetyScanner();

    public VariantMergePlan previewLocalMerge(
            MinecraftServer server,
            String projectName,
            String sourceVariantId
    ) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        String activeVariantId = this.activeVariantId(layout, project);
        if (activeVariantId.equals(sourceVariantId)) {
            throw new IllegalArgumentException("Cannot merge the active branch into itself");
        }
        return this.planMerge(layout, project, activeVariantId, layout, project, sourceVariantId);
    }

    public OperationHandle startLocalMerge(
            ServerLevel level,
            String projectName,
            String sourceVariantId,
            String author
    ) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        String activeVariantId = this.activeVariantId(layout, project);
        if (activeVariantId.equals(sourceVariantId)) {
            throw new IllegalArgumentException("Cannot merge the active branch into itself");
        }
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }
        if (HistoryCaptureManager.getInstance().snapshotDraft(level.getServer(), project.id().toString())
                .filter(draft -> !draft.isEmpty())
                .isPresent()) {
            throw new IllegalArgumentException("Discard or save the current recovery draft before merging branches");
        }

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "merge-variant",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> {
                    PreparedLocalMerge merge = this.prepareLocalMerge(
                            level,
                            layout,
                            project,
                            activeVariantId,
                            sourceVariantId,
                            author,
                            progressSink
                    );
                    return new PreparedApplyOperation(
                            merge.batches(),
                            () -> this.completeLocalMerge(
                                    level,
                                    layout,
                                    project,
                                    merge.draft(),
                                    sourceVariantId,
                                    merge.batches().size()
                            )
                    );
                }
        );
    }

    public VariantMergePlan previewMerge(
            MinecraftServer server,
            String targetProjectName,
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId
    ) throws IOException {
        ProjectLayout targetLayout = this.projectService.resolveLayout(server, targetProjectName);
        ProjectLayout sourceLayout = this.projectService.resolveLayout(server, sourceProjectName);
        BuildProject targetProject = this.projectRepository.load(targetLayout)
                .orElseThrow(() -> new IllegalArgumentException("Target project metadata is missing for " + targetProjectName));
        BuildProject sourceProject = this.projectRepository.load(sourceLayout)
                .orElseThrow(() -> new IllegalArgumentException("Source project metadata is missing for " + sourceProjectName));
        return this.planMerge(targetLayout, targetProject, targetVariantId, sourceLayout, sourceProject, sourceVariantId);
    }

    public OperationHandle startMerge(
            ServerLevel level,
            String targetProjectName,
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId,
            String author
    ) throws IOException {
        return this.startMerge(
                level,
                new VariantMergeApplyRequest(
                        targetProjectName,
                        sourceProjectName,
                        sourceVariantId,
                        targetVariantId,
                        false
                ),
                author
        );
    }

    public OperationHandle startMerge(
            ServerLevel level,
            VariantMergeApplyRequest request,
            String author
    ) throws IOException {
        ProjectLayout targetLayout = this.projectService.resolveLayout(level.getServer(), request.targetProjectName());
        ProjectLayout sourceLayout = this.projectService.resolveLayout(level.getServer(), request.sourceProjectName());
        BuildProject targetProject = this.projectRepository.load(targetLayout)
                .orElseThrow(() -> new IllegalArgumentException("Target project metadata is missing for " + request.targetProjectName()));
        BuildProject sourceProject = this.projectRepository.load(sourceLayout)
                .orElseThrow(() -> new IllegalArgumentException("Source project metadata is missing for " + request.sourceProjectName()));
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }

        return this.worldOperationManager.startBackgroundOperation(
                level,
                targetProject.id().toString(),
                "merge-variant",
                "blocks",
                LumaDebugLog.enabled(targetProject),
                progressSink -> {
                    progressSink.update(OperationStage.PREPARING, 0, 1, "Planning imported merge");
                    VariantMergePlan plan = this.planMerge(
                            targetLayout,
                            targetProject,
                            request.targetVariantId(),
                            sourceLayout,
                            sourceProject,
                            request.sourceVariantId()
                    );
                    this.requireTrustedImportedPackage(plan, targetLayout, sourceLayout, request.trustedPackageConfirmed());
                    List<StoredBlockChange> mergeChanges = plan.mergeChanges();
                    List<StoredEntityChange> mergeEntityChanges = plan.mergeEntityChanges();
                    if (mergeChanges.isEmpty() && mergeEntityChanges.isEmpty()) {
                        throw new IllegalArgumentException("Imported variant does not add any new changes");
                    }
                    int totalChanges = mergeChanges.size() + mergeEntityChanges.size();
                    progressSink.update(OperationStage.PREPARING, 0, Math.max(1, totalChanges), "Writing imported merge");
                    RecoveryDraft draft = new RecoveryDraft(
                            targetProject.id().toString(),
                            plan.targetVariantId(),
                            plan.targetHeadVersionId(),
                            author,
                            WorldMutationSource.SYSTEM,
                            Instant.now(),
                            Instant.now(),
                            mergeChanges,
                            mergeEntityChanges
                    );
                    ProjectVersion mergedVersion = this.versionService.writeVersion(
                            level,
                            targetLayout,
                            targetProject,
                            draft,
                            "Merged " + request.sourceVariantId() + " from " + request.sourceProjectName(),
                            author,
                            VersionKind.MERGE,
                            true,
                            "",
                            progressSink
                    );
                    this.recoveryRepository.appendJournalEntry(targetLayout, new RecoveryJournalEntry(
                            Instant.now(),
                            "variant-merged",
                            "Merged variant " + request.sourceVariantId() + " from " + request.sourceProjectName(),
                            mergedVersion.id(),
                            request.targetVariantId()
                    ));
                }
        );
    }

    private PreparedLocalMerge prepareLocalMerge(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            String activeVariantId,
            String sourceVariantId,
            String author,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        progressSink.update(OperationStage.PREPARING, 0, 1, "Planning branch merge");
        VariantMergePlan plan = this.planMerge(layout, project, activeVariantId, layout, project, sourceVariantId);
        List<StoredBlockChange> mergeChanges = plan.mergeChanges();
        List<StoredEntityChange> mergeEntityChanges = plan.mergeEntityChanges();
        if (mergeChanges.isEmpty() && mergeEntityChanges.isEmpty()) {
            throw new IllegalArgumentException("Source branch does not add any new changes");
        }

        String resolvedAuthor = author == null || author.isBlank() ? "Lumi" : author;
        Instant now = Instant.now();
        RecoveryDraft draft = new RecoveryDraft(
                project.id().toString(),
                activeVariantId,
                plan.targetHeadVersionId(),
                resolvedAuthor,
                WorldMutationSource.SYSTEM,
                now,
                now,
                mergeChanges,
                mergeEntityChanges
        );

        int totalChanges = mergeChanges.size() + mergeEntityChanges.size();
        progressSink.update(OperationStage.PREPARING, 0, Math.max(1, totalChanges), "Preparing branch merge");
        List<PreparedChunkBatch> batches = this.batchCollapser.collapse(this.batchPreparer.prepare(
                level,
                mergeChanges,
                mergeEntityChanges,
                true,
                (completed, total) -> progressSink.update(
                        OperationStage.PREPARING,
                        completed,
                        Math.max(1, total),
                        "Decoded merge changes"
                ),
                EntityApplyMode.DELTA
        ));
        return new PreparedLocalMerge(draft, batches);
    }

    private void completeLocalMerge(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft draft,
            String sourceVariantId,
            int batchCount
    ) throws IOException {
        ProjectVersion mergedVersion = this.versionService.writeVersion(
                level,
                layout,
                project,
                draft,
                "Merged " + sourceVariantId + " into " + draft.variantId(),
                draft.actor(),
                VersionKind.MERGE,
                true,
                progressSinkNoOp()
        );
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "local-variant-merged",
                "Merged branch " + sourceVariantId + " into active branch " + draft.variantId(),
                mergedVersion.id(),
                draft.variantId()
        ));
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        LumaDebugLog.log(
                project,
                "merge",
                "Completed local merge from {} into {} as {} using {} chunk batches",
                sourceVariantId,
                draft.variantId(),
                mergedVersion.id(),
                batchCount
        );
    }

    private String activeVariantId(ProjectLayout layout, BuildProject project) throws IOException {
        ProjectVariant activeVariant = this.resolveVariant(
                this.variantRepository.loadAll(layout),
                project.activeVariantId(),
                "Active"
        );
        if (activeVariant.headVersionId() == null || activeVariant.headVersionId().isBlank()) {
            throw new IllegalArgumentException("Active branch has no saved head");
        }
        return activeVariant.id();
    }

    private static WorldOperationManager.ProgressSink progressSinkNoOp() {
        return (stage, completedUnits, totalUnits, detail) -> {
        };
    }

    VariantMergePlan planMerge(
            ProjectLayout targetLayout,
            BuildProject targetProject,
            String targetVariantId,
            ProjectLayout sourceLayout,
            BuildProject sourceProject,
            String sourceVariantId
    ) throws IOException {
        this.validateCompatibility(targetProject, sourceProject);
        List<ProjectVersion> targetVersions = this.versionRepository.loadAll(targetLayout);
        List<ProjectVersion> sourceVersions = this.versionRepository.loadAll(sourceLayout);
        ProjectVariant targetVariant = this.resolveVariant(this.variantRepository.loadAll(targetLayout), targetVariantId, "Target");
        ProjectVariant sourceVariant = this.resolveVariant(this.variantRepository.loadAll(sourceLayout), sourceVariantId, "Source");
        if (targetVariant.headVersionId() == null || targetVariant.headVersionId().isBlank()) {
            throw new IllegalArgumentException("Target variant has no saved head");
        }
        if (sourceVariant.headVersionId() == null || sourceVariant.headVersionId().isBlank()) {
            throw new IllegalArgumentException("Source variant has no saved head");
        }

        Map<String, ProjectVersion> targetVersionMap = this.lineageService.versionMap(targetVersions);
        Map<String, ProjectVersion> sourceVersionMap = this.lineageService.versionMap(sourceVersions);
        String ancestorVersionId = this.commonAncestorId(
                targetLayout,
                targetVersionMap,
                targetVariant.headVersionId(),
                sourceLayout,
                sourceVersionMap,
                sourceVariant.headVersionId()
        );
        Map<BlockPoint, StateAccumulator> targetStates = this.collectStates(targetLayout, targetVersionMap, ancestorVersionId, targetVariant.headVersionId());
        Map<BlockPoint, StateAccumulator> sourceStates = this.collectStates(sourceLayout, sourceVersionMap, ancestorVersionId, sourceVariant.headVersionId());
        Map<String, EntityStateAccumulator> targetEntityStates = this.collectEntityStates(
                targetLayout,
                targetVersionMap,
                ancestorVersionId,
                targetVariant.headVersionId()
        );
        Map<String, EntityStateAccumulator> sourceEntityStates = this.collectEntityStates(
                sourceLayout,
                sourceVersionMap,
                ancestorVersionId,
                sourceVariant.headVersionId()
        );

        List<StoredBlockChange> mergeChanges = new ArrayList<>();
        LinkedHashSet<BlockPoint> allPositions = new LinkedHashSet<>();
        allPositions.addAll(targetStates.keySet());
        allPositions.addAll(sourceStates.keySet());
        for (BlockPoint pos : allPositions) {
            StatePayload ancestorState = this.resolveAncestorState(pos, targetStates, sourceStates);
            StatePayload targetFinal = targetStates.containsKey(pos) ? targetStates.get(pos).finalState() : ancestorState;
            StatePayload sourceFinal = sourceStates.containsKey(pos) ? sourceStates.get(pos).finalState() : ancestorState;
            boolean sourceChanged = !this.statesEqual(ancestorState, sourceFinal);
            if (!sourceChanged) {
                continue;
            }
            if (!this.statesEqual(targetFinal, sourceFinal)) {
                mergeChanges.add(new StoredBlockChange(pos, targetFinal, sourceFinal));
            }
        }
        List<StoredEntityChange> mergeEntityChanges = this.collectMergeEntityChanges(targetEntityStates, sourceEntityStates);

        VariantMergePlan plan = new VariantMergePlan(
                sourceProject.name(),
                sourceVariant.id(),
                sourceVariant.headVersionId(),
                targetProject.name(),
                targetVariant.id(),
                targetVariant.headVersionId(),
                ancestorVersionId,
                sourceStates.size(),
                targetStates.size(),
                List.copyOf(mergeChanges),
                mergeEntityChanges,
                HistoryPackageSafetyReport.clean()
        );
        if (targetLayout.root().toAbsolutePath().normalize().equals(sourceLayout.root().toAbsolutePath().normalize())) {
            return plan;
        }
        return this.withSafetyReport(plan, this.safetyScanner.scanMergePlan(plan));
    }

    private void requireTrustedImportedPackage(
            VariantMergePlan plan,
            ProjectLayout targetLayout,
            ProjectLayout sourceLayout,
            boolean trustedPackageConfirmed
    ) {
        if (targetLayout.root().toAbsolutePath().normalize().equals(sourceLayout.root().toAbsolutePath().normalize())) {
            return;
        }
        if (plan.safetyReport().requiresTrustedConfirmation() && !trustedPackageConfirmed) {
            throw new IllegalArgumentException("Imported package contains executable world-state data. Confirm that you trust this package before applying it.");
        }
    }

    private VariantMergePlan withSafetyReport(VariantMergePlan plan, HistoryPackageSafetyReport safetyReport) {
        return new VariantMergePlan(
                plan.sourceProjectName(),
                plan.sourceVariantId(),
                plan.sourceHeadVersionId(),
                plan.targetProjectName(),
                plan.targetVariantId(),
                plan.targetHeadVersionId(),
                plan.commonAncestorVersionId(),
                plan.sourceChangedBlocks(),
                plan.targetChangedBlocks(),
                plan.mergeChanges(),
                plan.mergeEntityChanges(),
                safetyReport
        );
    }

    private void validateCompatibility(BuildProject targetProject, BuildProject sourceProject) {
        if (!Objects.equals(targetProject.id(), sourceProject.id())) {
            throw new IllegalArgumentException("Imported history belongs to a different project lineage");
        }
        if (!Objects.equals(targetProject.dimensionId(), sourceProject.dimensionId())) {
            throw new IllegalArgumentException("Imported history targets a different dimension");
        }
    }

    private ProjectVariant resolveVariant(List<ProjectVariant> variants, String variantId, String label) {
        return variants.stream()
                .filter(variant -> variant.id().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(label + " variant not found: " + variantId));
    }

    private String commonAncestorId(
            ProjectLayout targetLayout,
            Map<String, ProjectVersion> targetVersionMap,
            String targetHeadVersionId,
            ProjectLayout sourceLayout,
            Map<String, ProjectVersion> sourceVersionMap,
            String sourceHeadVersionId
    ) {
        if (targetLayout.root().equals(sourceLayout.root())) {
            ProjectVersion targetHead = targetVersionMap.get(targetHeadVersionId);
            ProjectVersion sourceHead = targetVersionMap.get(sourceHeadVersionId);
            if (targetHead == null || sourceHead == null) {
                throw new IllegalArgumentException("Local merge branch head is missing");
            }
            return this.lineageService.commonAncestor(targetVersionMap, targetHead, sourceHead).id();
        }

        return this.lineageService.sharedSavedAncestorId(
                targetVersionMap,
                sourceVersionMap,
                sourceHeadVersionId
        );
    }

    private List<StoredEntityChange> collectMergeEntityChanges(
            Map<String, EntityStateAccumulator> targetStates,
            Map<String, EntityStateAccumulator> sourceStates
    ) {
        List<StoredEntityChange> mergeChanges = new ArrayList<>();
        LinkedHashSet<String> entityIds = new LinkedHashSet<>();
        entityIds.addAll(targetStates.keySet());
        entityIds.addAll(sourceStates.keySet());
        for (String entityId : entityIds) {
            EntityPayload ancestorState = this.resolveAncestorEntityState(entityId, targetStates, sourceStates);
            EntityPayload targetFinal = targetStates.containsKey(entityId) ? targetStates.get(entityId).finalState() : ancestorState;
            EntityPayload sourceFinal = sourceStates.containsKey(entityId) ? sourceStates.get(entityId).finalState() : ancestorState;
            boolean sourceChanged = !Objects.equals(ancestorState, sourceFinal);
            if (!sourceChanged) {
                continue;
            }
            if (!Objects.equals(targetFinal, sourceFinal)) {
                String entityType = sourceStates.containsKey(entityId)
                        ? sourceStates.get(entityId).entityType()
                        : targetStates.get(entityId).entityType();
                mergeChanges.add(new StoredEntityChange(entityId, entityType, targetFinal, sourceFinal));
            }
        }
        return List.copyOf(mergeChanges);
    }

    private Map<BlockPoint, StateAccumulator> collectStates(
            ProjectLayout layout,
            Map<String, ProjectVersion> versionMap,
            String ancestorVersionId,
            String targetHeadVersionId
    ) throws IOException {
        List<ProjectVersion> path = this.lineageService.pathFromAncestor(versionMap, ancestorVersionId, targetHeadVersionId);
        Map<BlockPoint, StateAccumulator> states = new LinkedHashMap<>();
        for (ProjectVersion version : path) {
            for (StoredBlockChange change : this.loadPatchWorldChanges(layout, version.patchIds()).blockChanges()) {
                states.compute(change.pos(), (pos, current) -> current == null
                        ? new StateAccumulator(change.oldValue(), change.newValue())
                        : current.withFinalState(change.newValue()));
            }
        }
        return states;
    }

    private Map<String, EntityStateAccumulator> collectEntityStates(
            ProjectLayout layout,
            Map<String, ProjectVersion> versionMap,
            String ancestorVersionId,
            String targetHeadVersionId
    ) throws IOException {
        List<ProjectVersion> path = this.lineageService.pathFromAncestor(versionMap, ancestorVersionId, targetHeadVersionId);
        Map<String, EntityStateAccumulator> states = new LinkedHashMap<>();
        for (ProjectVersion version : path) {
            for (StoredEntityChange change : this.loadPatchWorldChanges(layout, version.patchIds()).entityChanges()) {
                states.compute(change.entityId(), (entityId, current) -> current == null
                        ? new EntityStateAccumulator(change.oldValue(), change.newValue(), change.entityType())
                        : current.withFinalState(change.newValue(), change.entityType()));
            }
        }
        return states;
    }

    private PatchWorldChanges loadPatchWorldChanges(ProjectLayout layout, List<String> patchIds) throws IOException {
        List<StoredBlockChange> blockChanges = new ArrayList<>();
        List<StoredEntityChange> entityChanges = new ArrayList<>();
        for (String patchId : patchIds) {
            var metadata = this.patchMetaRepository.load(layout, patchId)
                    .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
            PatchWorldChanges changes = this.patchDataRepository.loadWorldChanges(layout, metadata);
            blockChanges.addAll(changes.blockChanges());
            entityChanges.addAll(changes.entityChanges());
        }
        return new PatchWorldChanges(blockChanges, entityChanges);
    }

    private StatePayload resolveAncestorState(
            BlockPoint pos,
            Map<BlockPoint, StateAccumulator> targetStates,
            Map<BlockPoint, StateAccumulator> sourceStates
    ) {
        if (targetStates.containsKey(pos)) {
            return targetStates.get(pos).initialState();
        }
        return sourceStates.get(pos).initialState();
    }

    private EntityPayload resolveAncestorEntityState(
            String entityId,
            Map<String, EntityStateAccumulator> targetStates,
            Map<String, EntityStateAccumulator> sourceStates
    ) {
        if (targetStates.containsKey(entityId)) {
            return targetStates.get(entityId).initialState();
        }
        return sourceStates.get(entityId).initialState();
    }

    private boolean statesEqual(StatePayload left, StatePayload right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equalsState(right);
    }

    private record StateAccumulator(StatePayload initialState, StatePayload finalState) {

        private StateAccumulator withFinalState(StatePayload state) {
            return new StateAccumulator(this.initialState, state);
        }
    }

    private record EntityStateAccumulator(EntityPayload initialState, EntityPayload finalState, String entityType) {

        private EntityStateAccumulator withFinalState(EntityPayload state, String type) {
            return new EntityStateAccumulator(
                    this.initialState,
                    state,
                    type == null || type.isBlank() ? this.entityType : type
            );
        }
    }

    private record PreparedLocalMerge(RecoveryDraft draft, List<PreparedChunkBatch> batches) {
    }
}
