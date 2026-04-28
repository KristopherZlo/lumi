package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.MinecraftServer;

public final class DiffService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();

    public VersionDiff compareVersions(MinecraftServer server, String projectName, String leftVersionId, String rightVersionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

        ProjectVersion left = this.resolveVersion(layout, versions, leftVersionId);
        ProjectVersion right = this.resolveVersion(layout, versions, rightVersionId);
        ProjectVersion ancestor = this.findCommonAncestor(versionMap, left, right);

        Map<BlockPoint, StateAccumulator> leftStates = this.collectVersionStates(layout, versionMap, ancestor, left);
        Map<BlockPoint, StateAccumulator> rightStates = this.collectVersionStates(layout, versionMap, ancestor, right);
        Map<String, EntityStateAccumulator> leftEntityStates = this.collectEntityVersionStates(layout, versionMap, ancestor, left);
        Map<String, EntityStateAccumulator> rightEntityStates = this.collectEntityVersionStates(layout, versionMap, ancestor, right);

        Set<BlockPoint> allPositions = new HashSet<>();
        allPositions.addAll(leftStates.keySet());
        allPositions.addAll(rightStates.keySet());

        List<DiffBlockEntry> changedBlocks = new ArrayList<>();
        Set<Long> changedChunks = new HashSet<>();
        for (BlockPoint pos : allPositions) {
            StatePayload leftState = leftStates.containsKey(pos)
                    ? leftStates.get(pos).finalState()
                    : rightStates.get(pos).initialState();
            StatePayload rightState = rightStates.containsKey(pos)
                    ? rightStates.get(pos).finalState()
                    : leftStates.get(pos).initialState();
            if (this.statesEqual(leftState, rightState)) {
                continue;
            }

            changedBlocks.add(this.diffEntry(pos, leftState, rightState));
            changedChunks.add(chunkKey(pos));
        }
        List<StoredEntityChange> changedEntities = this.diffEntityStates(leftEntityStates, rightEntityStates);
        for (StoredEntityChange change : changedEntities) {
            changedChunks.add(chunkKey(change.chunk()));
        }

        changedBlocks.sort(java.util.Comparator.comparing((DiffBlockEntry entry) -> entry.pos().x())
                .thenComparing(entry -> entry.pos().y())
                .thenComparing(entry -> entry.pos().z()));
        return new VersionDiff(left.id(), right.id(), changedBlocks, changedChunks.size(), changedEntities);
    }

    public VersionDiff compareVersionToParent(MinecraftServer server, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        if (version.parentVersionId() == null || version.parentVersionId().isBlank()) {
            List<DiffBlockEntry> changedBlocks = new ArrayList<>();
            Set<Long> changedChunks = new HashSet<>();
            PatchWorldChanges worldChanges = this.loadPatchWorldChanges(layout, version.patchIds());
            for (StoredBlockChange change : worldChanges.blockChanges()) {
                if (this.statesEqual(change.oldValue(), change.newValue())) {
                    continue;
                }
                changedBlocks.add(this.diffEntry(change.pos(), change.oldValue(), change.newValue()));
                changedChunks.add(chunkKey(change.pos()));
            }
            List<StoredEntityChange> changedEntities = this.changedEntityChanges(worldChanges.entityChanges());
            for (StoredEntityChange change : changedEntities) {
                changedChunks.add(chunkKey(change.chunk()));
            }

            changedBlocks.sort(java.util.Comparator.comparing((DiffBlockEntry entry) -> entry.pos().x())
                    .thenComparing(entry -> entry.pos().y())
                    .thenComparing(entry -> entry.pos().z()));
            return new VersionDiff(version.id(), version.id(), changedBlocks, changedChunks.size(), changedEntities);
        }

        return this.compareVersions(server, projectName, version.parentVersionId(), version.id());
    }

    public VersionDiff compareVersionToCurrentState(MinecraftServer server, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        ProjectVersion targetVersion = this.versionRepository.load(layout, versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active branch is missing for " + projectName));

        String activeHeadVersionId = activeVariant.headVersionId();
        if (activeHeadVersionId == null || activeHeadVersionId.isBlank()) {
            throw new IllegalArgumentException("Current branch has no committed head yet");
        }

        VersionDiff baseDiff = this.compareVersions(server, projectName, targetVersion.id(), activeHeadVersionId);
        RecoveryDraft draft = HistoryCaptureManager.getInstance().snapshotDraft(server, project.id().toString()).orElse(null);
        if (draft == null || draft.isEmpty()) {
            return baseDiff;
        }

        return this.applyDraft(baseDiff, draft.changes(), draft.entityChanges());
    }

    public List<ProjectVersion> listVersions(MinecraftServer server, String projectName) throws IOException {
        return this.versionRepository.loadAll(this.projectService.resolveLayout(server, projectName));
    }

    public List<ProjectVariant> listVariants(MinecraftServer server, String projectName) throws IOException {
        return this.variantRepository.loadAll(this.projectService.resolveLayout(server, projectName));
    }

    public String extractBlockId(String state) {
        return DiffBlockEntry.blockIdFromSnbt(state);
    }

    private ProjectVersion resolveVersion(ProjectLayout layout, List<ProjectVersion> versions, String versionId) throws IOException {
        if (versionId != null && !versionId.isBlank()) {
            return this.versionRepository.load(layout, versionId)
                    .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        }

        if (versions.isEmpty()) {
            throw new IllegalArgumentException("No versions available");
        }

        return versions.get(versions.size() - 1);
    }

    private ProjectVersion findCommonAncestor(Map<String, ProjectVersion> versionMap, ProjectVersion left, ProjectVersion right) {
        Set<String> leftAncestors = new HashSet<>();
        ProjectVersion cursor = left;
        while (cursor != null) {
            leftAncestors.add(cursor.id());
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }

        cursor = right;
        while (cursor != null) {
            if (leftAncestors.contains(cursor.id())) {
                return cursor;
            }

            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }

        throw new IllegalArgumentException("Versions do not share a common ancestor");
    }

    private Map<BlockPoint, StateAccumulator> collectVersionStates(
            ProjectLayout layout,
            Map<String, ProjectVersion> versionMap,
            ProjectVersion ancestor,
            ProjectVersion target
    ) throws IOException {
        List<ProjectVersion> path = this.pathFromAncestor(versionMap, ancestor, target);
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

    private List<ProjectVersion> pathFromAncestor(Map<String, ProjectVersion> versionMap, ProjectVersion ancestor, ProjectVersion target) {
        List<ProjectVersion> reversed = new ArrayList<>();
        ProjectVersion cursor = target;
        while (cursor != null && !cursor.id().equals(ancestor.id())) {
            reversed.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }

        List<ProjectVersion> path = new ArrayList<>();
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return path;
    }

    private Map<String, EntityStateAccumulator> collectEntityVersionStates(
            ProjectLayout layout,
            Map<String, ProjectVersion> versionMap,
            ProjectVersion ancestor,
            ProjectVersion target
    ) throws IOException {
        List<ProjectVersion> path = this.pathFromAncestor(versionMap, ancestor, target);
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

    private List<StoredEntityChange> diffEntityStates(
            Map<String, EntityStateAccumulator> leftStates,
            Map<String, EntityStateAccumulator> rightStates
    ) {
        List<StoredEntityChange> changedEntities = new ArrayList<>();
        Set<String> entityIds = new HashSet<>();
        entityIds.addAll(leftStates.keySet());
        entityIds.addAll(rightStates.keySet());
        for (String entityId : entityIds) {
            EntityPayload leftState = leftStates.containsKey(entityId)
                    ? leftStates.get(entityId).finalState()
                    : rightStates.get(entityId).initialState();
            EntityPayload rightState = rightStates.containsKey(entityId)
                    ? rightStates.get(entityId).finalState()
                    : leftStates.get(entityId).initialState();
            if (Objects.equals(leftState, rightState)) {
                continue;
            }
            String entityType = rightStates.containsKey(entityId)
                    ? rightStates.get(entityId).entityType()
                    : leftStates.get(entityId).entityType();
            changedEntities.add(new StoredEntityChange(entityId, entityType, leftState, rightState));
        }
        changedEntities.sort(java.util.Comparator.comparing(StoredEntityChange::entityId));
        return List.copyOf(changedEntities);
    }

    private ChangeType changeType(StatePayload leftState, StatePayload rightState) {
        boolean leftAir = this.isAir(leftState);
        boolean rightAir = this.isAir(rightState);
        if (leftAir && !rightAir) {
            return ChangeType.ADDED;
        }
        if (!leftAir && rightAir) {
            return ChangeType.REMOVED;
        }
        return ChangeType.CHANGED;
    }

    private ChangeType changeType(String leftState, String rightState) {
        boolean leftAir = this.isAir(leftState);
        boolean rightAir = this.isAir(rightState);
        if (leftAir && !rightAir) {
            return ChangeType.ADDED;
        }
        if (!leftAir && rightAir) {
            return ChangeType.REMOVED;
        }
        return ChangeType.CHANGED;
    }

    private boolean isAir(String state) {
        return "minecraft:air".equals(this.extractBlockId(state));
    }

    ChangeType classifyStateChange(StatePayload leftState, StatePayload rightState) {
        return this.changeType(leftState, rightState);
    }

    boolean statesEqual(StatePayload leftState, StatePayload rightState) {
        if (leftState == rightState) {
            return true;
        }
        if (leftState == null || rightState == null) {
            return false;
        }
        return leftState.equalsState(rightState);
    }

    VersionDiff applyDraft(
            VersionDiff baseDiff,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) {
        Map<BlockPoint, DiffBlockEntry> changed = new LinkedHashMap<>();
        for (DiffBlockEntry entry : baseDiff.changedBlocks()) {
            changed.put(entry.pos(), entry);
        }

        for (StoredBlockChange change : changes) {
            String oldState = this.toStateString(change.oldValue());
            String newState = this.toStateString(change.newValue());
            DiffBlockEntry existing = changed.get(change.pos());
            String leftState = existing == null ? oldState : existing.leftState();
            String rightState = newState;
            if (Objects.equals(leftState, rightState)) {
                changed.remove(change.pos());
                continue;
            }

            changed.put(change.pos(), new DiffBlockEntry(
                    change.pos(),
                    leftState,
                    rightState,
                    this.changeType(leftState, rightState),
                    existing == null ? change.oldValue().blockId() : existing.leftBlockId(),
                    change.newValue().blockId()
            ));
        }

        List<DiffBlockEntry> result = new ArrayList<>(changed.values());
        result.sort(java.util.Comparator.comparing((DiffBlockEntry entry) -> entry.pos().x())
                .thenComparing(entry -> entry.pos().y())
                .thenComparing(entry -> entry.pos().z()));

        Set<Long> changedChunks = new HashSet<>();
        for (DiffBlockEntry entry : result) {
            changedChunks.add(chunkKey(entry.pos()));
        }
        List<StoredEntityChange> changedEntities = VersionService.mergeEntityChanges(baseDiff.changedEntities(), entityChanges);
        for (StoredEntityChange change : changedEntities) {
            changedChunks.add(chunkKey(change.chunk()));
        }

        return new VersionDiff(baseDiff.leftVersionId(), "current", result, changedChunks.size(), changedEntities);
    }

    private PatchWorldChanges loadPatchWorldChanges(ProjectLayout layout, List<String> patchIds) throws IOException {
        List<StoredBlockChange> changes = new ArrayList<>();
        List<StoredEntityChange> entityChanges = new ArrayList<>();
        for (String patchId : patchIds) {
            PatchMetadataHolder metadata = this.loadPatchMetadata(layout, patchId);
            PatchWorldChanges worldChanges = this.patchDataRepository.loadWorldChanges(layout, metadata.metadata());
            changes.addAll(worldChanges.blockChanges());
            entityChanges.addAll(worldChanges.entityChanges());
        }
        return new PatchWorldChanges(changes, entityChanges);
    }

    private PatchMetadataHolder loadPatchMetadata(ProjectLayout layout, String patchId) throws IOException {
        return new PatchMetadataHolder(
                this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId))
        );
    }

    private static long chunkKey(BlockPoint pos) {
        long chunkX = pos.x() >> 4;
        long chunkZ = pos.z() >> 4;
        return (chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static long chunkKey(io.github.luma.domain.model.ChunkPoint chunk) {
        return (((long) chunk.x()) << 32) ^ (((long) chunk.z()) & 0xffffffffL);
    }

    private DiffBlockEntry diffEntry(BlockPoint pos, StatePayload leftState, StatePayload rightState) {
        return new DiffBlockEntry(
                pos,
                this.toStateString(leftState),
                this.toStateString(rightState),
                this.changeType(leftState, rightState),
                leftState == null ? "minecraft:air" : leftState.blockId(),
                rightState == null ? "minecraft:air" : rightState.blockId()
        );
    }

    private String toStateString(StatePayload state) {
        return state == null ? "" : state.toStateSnbt();
    }

    private boolean isAir(StatePayload state) {
        return state == null || "minecraft:air".equals(state.blockId());
    }

    private List<StoredEntityChange> changedEntityChanges(List<StoredEntityChange> changes) {
        return (changes == null ? List.<StoredEntityChange>of() : changes).stream()
                .filter(change -> !change.isNoOp())
                .sorted(java.util.Comparator.comparing(StoredEntityChange::entityId))
                .toList();
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

    private record PatchMetadataHolder(io.github.luma.domain.model.PatchMetadata metadata) {
    }
}
