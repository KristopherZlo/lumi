package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;

public final class DiffService {

    private static final Pattern BLOCK_NAME_PATTERN = Pattern.compile("Name:\\s*\"([^\"]+)\"");

    private final ProjectService projectService = new ProjectService();
    private final VersionRepository versionRepository = new VersionRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final PatchRepository patchRepository = new PatchRepository();

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

        Set<BlockPoint> allPositions = new HashSet<>();
        allPositions.addAll(leftStates.keySet());
        allPositions.addAll(rightStates.keySet());

        List<DiffBlockEntry> changedBlocks = new ArrayList<>();
        Set<Long> changedChunks = new HashSet<>();
        for (BlockPoint pos : allPositions) {
            String leftState = leftStates.containsKey(pos) ? leftStates.get(pos).finalState() : rightStates.get(pos).initialState();
            String rightState = rightStates.containsKey(pos) ? rightStates.get(pos).finalState() : leftStates.get(pos).initialState();
            if (leftState.equals(rightState)) {
                continue;
            }

            changedBlocks.add(new DiffBlockEntry(pos, leftState, rightState, this.changeType(leftState, rightState)));
            changedChunks.add(chunkKey(pos));
        }

        changedBlocks.sort(java.util.Comparator.comparing((DiffBlockEntry entry) -> entry.pos().x())
                .thenComparing(entry -> entry.pos().y())
                .thenComparing(entry -> entry.pos().z()));
        return new VersionDiff(left.id(), right.id(), changedBlocks, changedChunks.size());
    }

    public VersionDiff compareVersionToParent(MinecraftServer server, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        if (version.parentVersionId() == null || version.parentVersionId().isBlank()) {
            return new VersionDiff(version.id(), version.id(), List.of(), 0);
        }

        return this.compareVersions(server, projectName, version.parentVersionId(), version.id());
    }

    public List<ProjectVersion> listVersions(MinecraftServer server, String projectName) throws IOException {
        return this.versionRepository.loadAll(this.projectService.resolveLayout(server, projectName));
    }

    public List<ProjectVariant> listVariants(MinecraftServer server, String projectName) throws IOException {
        return this.variantRepository.loadAll(this.projectService.resolveLayout(server, projectName));
    }

    public String extractBlockId(String state) {
        if (state == null || state.isBlank()) {
            return "minecraft:air";
        }

        Matcher matcher = BLOCK_NAME_PATTERN.matcher(state);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return "minecraft:unknown";
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
            for (String patchId : version.patchIds()) {
                for (BlockChangeRecord change : this.patchRepository.loadChanges(layout, patchId)) {
                    states.compute(change.pos(), (pos, current) -> current == null
                            ? new StateAccumulator(change.oldState(), change.newState())
                            : current.withFinalState(change.newState()));
                }
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

    private static long chunkKey(BlockPoint pos) {
        long chunkX = pos.x() >> 4;
        long chunkZ = pos.z() >> 4;
        return (chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private record StateAccumulator(String initialState, String finalState) {

        private StateAccumulator withFinalState(String state) {
            return new StateAccumulator(this.initialState, state);
        }
    }
}
