package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.storage.repository.ProjectDirtyScopeRepository;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns coalesced runtime dirty scopes and their asynchronous persistence. */
final class ProjectDirtyScopeManager {

    private final CapturePersistenceCoordinator persistenceCoordinator;
    private final ProjectDirtyScopeRepository repository;
    private final Map<String, ScopeEntry> scopes = new HashMap<>();

    ProjectDirtyScopeManager(CapturePersistenceCoordinator persistenceCoordinator) {
        this(persistenceCoordinator, new ProjectDirtyScopeRepository());
    }

    ProjectDirtyScopeManager(
            CapturePersistenceCoordinator persistenceCoordinator,
            ProjectDirtyScopeRepository repository
    ) {
        this.persistenceCoordinator = persistenceCoordinator;
        this.repository = repository;
    }

    synchronized boolean markBlockSection(TrackedProject project, ChunkSectionPoint section) {
        return this.markBlockSections(project, section == null ? List.of() : List.of(section));
    }

    synchronized boolean markBlockSections(
            TrackedProject project,
            Collection<ChunkSectionPoint> sections
    ) {
        ScopeEntry entry = this.entry(project);
        if (!entry.scope.markBlockSections(sections)) {
            return false;
        }
        this.enqueue(entry);
        return true;
    }

    synchronized boolean markEntityChunk(TrackedProject project, ChunkPoint chunk) {
        ScopeEntry entry = this.entry(project);
        if (!entry.scope.markEntityChunk(chunk)) {
            return false;
        }
        this.enqueue(entry);
        return true;
    }

    ProjectDirtyScope loadDurable(TrackedProject project) throws IOException {
        String projectId = project.project().id().toString();
        this.persistenceCoordinator.drainDirtyScopeFlushes(projectId, project.project().name());
        return this.repository.load(project.layout()).orElseGet(() -> this.snapshot(project));
    }

    void clear(TrackedProject project) throws IOException {
        String projectId = project.project().id().toString();
        this.persistenceCoordinator.deleteDirtyScope(project.layout(), projectId, project.project().name());
        synchronized (this) {
            this.scopes.remove(projectId);
        }
    }

    void drainAll() throws IOException {
        List<ScopeEntry> entries;
        synchronized (this) {
            entries = List.copyOf(this.scopes.values());
        }
        for (ScopeEntry entry : entries) {
            this.persistenceCoordinator.drainDirtyScopeFlushes(entry.projectId, entry.projectName);
        }
    }

    private synchronized ProjectDirtyScope snapshot(TrackedProject project) {
        ScopeEntry entry = this.scopes.get(project.project().id().toString());
        return entry == null ? this.newScope(project) : entry.scope.copy();
    }

    private ScopeEntry entry(TrackedProject project) {
        if (project == null) {
            throw new IllegalArgumentException("tracked project is required");
        }
        String projectId = project.project().id().toString();
        ScopeEntry existing = this.scopes.get(projectId);
        ProjectDirtyScope expected = this.newScope(project);
        if (existing != null) {
            if (!sameBase(existing.scope, expected)) {
                throw new IllegalStateException("Active project head changed with a pending dirty scope");
            }
            return existing;
        }
        ScopeEntry created = new ScopeEntry(projectId, project.project().name(), project.layout(), expected);
        this.scopes.put(projectId, created);
        return created;
    }

    private ProjectDirtyScope newScope(TrackedProject project) {
        ProjectVariant active = project.variants().stream()
                .filter(variant -> variant.id().equals(project.project().activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Active project variant is missing"));
        return ProjectDirtyScope.empty(
                project.project().id().toString(),
                active.id(),
                active.headVersionId()
        );
    }

    private void enqueue(ScopeEntry entry) {
        this.persistenceCoordinator.enqueueDirtyScopeFlush(
                entry.layout,
                entry.projectId,
                entry.projectName,
                entry.scope
        );
    }

    private static boolean sameBase(ProjectDirtyScope left, ProjectDirtyScope right) {
        return left.projectId().equals(right.projectId())
                && left.variantId().equals(right.variantId())
                && left.baseVersionId().equals(right.baseVersionId());
    }

    private record ScopeEntry(
            String projectId,
            String projectName,
            io.github.luma.storage.ProjectLayout layout,
            ProjectDirtyScope scope
    ) {
    }
}
