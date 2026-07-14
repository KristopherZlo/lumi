package io.github.luma.domain.service;

import io.github.luma.domain.model.HistoryProtectionState;
import io.github.luma.domain.model.HistoryProtectionStatus;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.HistoryProtectionRepository;
import io.github.luma.storage.repository.ProjectDirtyScopeRepository;
import io.github.luma.storage.repository.ProjectRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Derives visible protection state and records durable reliability failures. */
public final class HistoryProtectionService {

    private final HistoryProtectionRepository protectionRepository;
    private final ProjectDirtyScopeRepository dirtyScopeRepository;
    private final ProjectRepository projectRepository;

    public HistoryProtectionService() {
        this(new HistoryProtectionRepository(), new ProjectDirtyScopeRepository(), new ProjectRepository());
    }

    HistoryProtectionService(
            HistoryProtectionRepository protectionRepository,
            ProjectDirtyScopeRepository dirtyScopeRepository,
            ProjectRepository projectRepository
    ) {
        this.protectionRepository = Objects.requireNonNull(protectionRepository, "protectionRepository");
        this.dirtyScopeRepository = Objects.requireNonNull(dirtyScopeRepository, "dirtyScopeRepository");
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    }

    public HistoryProtectionStatus load(ProjectLayout layout, OperationSnapshot operation) throws IOException {
        var degraded = this.protectionRepository.loadDegraded(layout);
        if (degraded.isPresent()) {
            return degraded.get();
        }
        if (operation == null || operation.terminal()) {
            return HistoryProtectionStatus.protectedStatus();
        }
        if (!reliabilityOperation(operation.handle().label())) {
            return HistoryProtectionStatus.protectedStatus();
        }
        HistoryProtectionState state = savingOperation(operation.handle().label())
                ? HistoryProtectionState.SAVING : HistoryProtectionState.RESTORING;
        return HistoryProtectionStatus.active(state, operation.detail(), operation.updatedAt());
    }

    public boolean hasSafetyChanges(ProjectLayout layout) throws IOException {
        return this.dirtyScopeRepository.load(layout).filter(scope -> !scope.isEmpty()).isPresent();
    }

    public boolean hasSafetyChanges(ProjectLayout layout, WorkZone zone) throws IOException {
        if (zone == null) {
            return false;
        }
        return this.dirtyScopeRepository.load(layout)
                .stream()
                .flatMap(scope -> scope.blockSections().stream())
                .anyMatch(section -> zone.contains(new WorkZoneCell(
                        section.chunkX(),
                        section.sectionY(),
                        section.chunkZ()
                )));
    }

    public void markDegraded(ProjectLayout layout, String detail) throws IOException {
        this.protectionRepository.saveDegraded(layout, HistoryProtectionStatus.degraded(detail, Instant.now()));
    }

    public void recordOperationFailure(MinecraftServer server, OperationHandle handle, String detail) throws IOException {
        if (server == null || handle == null || !reliabilityOperation(handle.label())) {
            return;
        }
        var projectsRoot = server.getWorldPath(LevelResource.ROOT).resolve("lumi").resolve("projects");
        ProjectLayout layout = this.projectRepository.findLayoutByProjectId(projectsRoot, handle.projectId())
                .orElseThrow(() -> new IOException("Project layout is missing for " + handle.projectId()));
        this.markDegraded(layout, detail);
    }

    private static boolean savingOperation(String label) {
        return "save-version".equals(label) || "amend-version".equals(label);
    }

    private static boolean reliabilityOperation(String label) {
        return savingOperation(label)
                || "restore-version".equals(label)
                || "partial-restore".equals(label)
                || "zone-restore".equals(label)
                || "quick-rollback".equals(label)
                || "merge-variant".equals(label)
                || "recovery".equals(label)
                || "restore-draft".equals(label);
    }
}
