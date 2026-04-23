package io.github.luma.domain.service;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        ProjectLayout targetLayout = this.projectService.resolveLayout(level.getServer(), targetProjectName);
        ProjectLayout sourceLayout = this.projectService.resolveLayout(level.getServer(), sourceProjectName);
        BuildProject targetProject = this.projectRepository.load(targetLayout)
                .orElseThrow(() -> new IllegalArgumentException("Target project metadata is missing for " + targetProjectName));
        BuildProject sourceProject = this.projectRepository.load(sourceLayout)
                .orElseThrow(() -> new IllegalArgumentException("Source project metadata is missing for " + sourceProjectName));
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }

        VariantMergePlan plan = this.planMerge(targetLayout, targetProject, targetVariantId, sourceLayout, sourceProject, sourceVariantId);
        if (plan.hasConflicts()) {
            throw new IllegalArgumentException("Merge conflicts must be resolved before applying the merge");
        }
        if (plan.mergeChanges().isEmpty()) {
            throw new IllegalArgumentException("Imported variant does not add any new changes");
        }

        return this.worldOperationManager.startBackgroundOperation(
                level,
                targetProject.id().toString(),
                "merge-variant",
                "blocks",
                LumaDebugLog.enabled(targetProject),
                progressSink -> {
                    progressSink.update(OperationStage.PREPARING, 0, plan.mergeChanges().size(), "Preparing merge");
                    RecoveryDraft draft = new RecoveryDraft(
                            targetProject.id().toString(),
                            plan.targetVariantId(),
                            plan.targetHeadVersionId(),
                            author,
                            WorldMutationSource.SYSTEM,
                            Instant.now(),
                            Instant.now(),
                            plan.mergeChanges()
                    );
                    ProjectVersion mergedVersion = this.versionService.writeVersion(
                            level,
                            targetLayout,
                            targetProject,
                            draft,
                            "Merged " + sourceVariantId + " from " + sourceProjectName,
                            author,
                            VersionKind.MANUAL,
                            true,
                            "",
                            progressSink
                    );
                    this.recoveryRepository.appendJournalEntry(targetLayout, new RecoveryJournalEntry(
                            Instant.now(),
                            "variant-merged",
                            "Merged variant " + sourceVariantId + " from " + sourceProjectName,
                            mergedVersion.id(),
                            targetVariantId
                    ));
                }
        );
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

        Map<String, ProjectVersion> targetVersionMap = this.versionMap(targetVersions);
        Map<String, ProjectVersion> sourceVersionMap = this.versionMap(sourceVersions);
        String ancestorVersionId = this.findSharedAncestor(targetVersionMap, sourceVersionMap, sourceVariant.headVersionId());
        Map<BlockPoint, StateAccumulator> targetStates = this.collectStates(targetLayout, targetVersionMap, ancestorVersionId, targetVariant.headVersionId());
        Map<BlockPoint, StateAccumulator> sourceStates = this.collectStates(sourceLayout, sourceVersionMap, ancestorVersionId, sourceVariant.headVersionId());

        List<StoredBlockChange> mergeChanges = new ArrayList<>();
        LinkedHashSet<BlockPoint> conflictPositions = new LinkedHashSet<>();
        LinkedHashSet<BlockPoint> allPositions = new LinkedHashSet<>();
        allPositions.addAll(targetStates.keySet());
        allPositions.addAll(sourceStates.keySet());
        for (BlockPoint pos : allPositions) {
            StatePayload ancestorState = this.resolveAncestorState(pos, targetStates, sourceStates);
            StatePayload targetFinal = targetStates.containsKey(pos) ? targetStates.get(pos).finalState() : ancestorState;
            StatePayload sourceFinal = sourceStates.containsKey(pos) ? sourceStates.get(pos).finalState() : ancestorState;
            boolean targetChanged = !this.statesEqual(ancestorState, targetFinal);
            boolean sourceChanged = !this.statesEqual(ancestorState, sourceFinal);
            if (!sourceChanged) {
                continue;
            }
            if (targetChanged && !this.statesEqual(targetFinal, sourceFinal)) {
                conflictPositions.add(pos);
                continue;
            }
            if (!this.statesEqual(targetFinal, sourceFinal)) {
                mergeChanges.add(new StoredBlockChange(pos, targetFinal, sourceFinal));
            }
        }

        return new VariantMergePlan(
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
                List.copyOf(conflictPositions)
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

    private String findSharedAncestor(
            Map<String, ProjectVersion> targetVersionMap,
            Map<String, ProjectVersion> sourceVersionMap,
            String sourceHeadVersionId
    ) {
        ProjectVersion cursor = sourceVersionMap.get(sourceHeadVersionId);
        while (cursor != null) {
            ProjectVersion targetCandidate = targetVersionMap.get(cursor.id());
            if (targetCandidate != null && targetCandidate.equals(cursor)) {
                return targetCandidate.id();
            }
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : sourceVersionMap.get(cursor.parentVersionId());
        }
        throw new IllegalArgumentException("Imported variant does not share a common saved ancestor with the target project");
    }

    private Map<String, ProjectVersion> versionMap(List<ProjectVersion> versions) {
        Map<String, ProjectVersion> versionMap = new LinkedHashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }
        return versionMap;
    }

    private Map<BlockPoint, StateAccumulator> collectStates(
            ProjectLayout layout,
            Map<String, ProjectVersion> versionMap,
            String ancestorVersionId,
            String targetHeadVersionId
    ) throws IOException {
        List<ProjectVersion> path = this.pathFromAncestor(versionMap, ancestorVersionId, targetHeadVersionId);
        Map<BlockPoint, StateAccumulator> states = new LinkedHashMap<>();
        for (ProjectVersion version : path) {
            for (StoredBlockChange change : this.loadPatchChanges(layout, version.patchIds())) {
                states.compute(change.pos(), (pos, current) -> current == null
                        ? new StateAccumulator(change.oldValue(), change.newValue())
                        : current.withFinalState(change.newValue()));
            }
        }
        return states;
    }

    private List<ProjectVersion> pathFromAncestor(
            Map<String, ProjectVersion> versionMap,
            String ancestorVersionId,
            String targetHeadVersionId
    ) {
        List<ProjectVersion> reversed = new ArrayList<>();
        ProjectVersion cursor = versionMap.get(targetHeadVersionId);
        while (cursor != null && !cursor.id().equals(ancestorVersionId)) {
            reversed.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }
        List<ProjectVersion> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return path;
    }

    private List<StoredBlockChange> loadPatchChanges(ProjectLayout layout, List<String> patchIds) throws IOException {
        List<StoredBlockChange> changes = new ArrayList<>();
        for (String patchId : patchIds) {
            var metadata = this.patchMetaRepository.load(layout, patchId)
                    .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
            changes.addAll(this.patchDataRepository.loadChanges(layout, metadata));
        }
        return changes;
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
}
