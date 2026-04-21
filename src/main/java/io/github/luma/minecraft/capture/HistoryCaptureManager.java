package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Captures tracked world edits into project-scoped tracked buffers.
 *
 * <p>This manager owns the active in-memory capture sessions, baseline chunk
 * capture for whole-dimension workspaces, periodic recovery draft flushing, and
 * translation between runtime buffers and persisted recovery storage.
 */
public final class HistoryCaptureManager {

    private static final HistoryCaptureManager INSTANCE = new HistoryCaptureManager();
    private static final Duration ACTIVE_DRAFT_FLUSH_INTERVAL = Duration.ofSeconds(3);

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final Map<String, TrackedChangeBuffer> activeBuffers = new HashMap<>();
    private final Map<String, CachedProjects> projectCaches = new HashMap<>();
    private final Map<String, Instant> lastDraftFlushes = new HashMap<>();
    private final Set<String> dirtySessions = new HashSet<>();

    private HistoryCaptureManager() {
    }

    public static HistoryCaptureManager getInstance() {
        return INSTANCE;
    }

    /**
     * Records one block mutation for any tracked source except internal restore
     * application.
     *
     * <p>The manager resolves matching projects, creates a world workspace on
     * demand if none exist, captures baseline chunk state when required, and
     * merges the change into the active {@link TrackedChangeBuffer}.
     */
    public void recordBlockChange(
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity
    ) {
        io.github.luma.domain.model.WorldMutationSource source = WorldMutationContext.currentSource();
        if (!shouldCaptureMutation(source)) {
            return;
        }

        try {
            Instant now = Instant.now();
            List<TrackedProject> matchingProjects = this.matchingProjects(level, pos);
            if (matchingProjects.isEmpty()) {
                this.projectService.ensureWorldProject(level, defaultActor(source));
                this.invalidateProjectCache(level.getServer());
                matchingProjects = this.matchingProjects(level, pos);
                LumaMod.LOGGER.info("Created world workspace automatically for dimension {}", level.dimension().identifier());
            }

            if (matchingProjects.isEmpty()) {
                LumaDebugLog.log(
                        "capture",
                        "Skipped mutation at {} in {} because no tracked workspace matched source={}",
                        pos,
                        level.dimension().identifier(),
                        source
                );
            }

            for (TrackedProject trackedProject : matchingProjects) {
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Recording {} mutation for project {} at {} in {}: {} -> {}",
                        source,
                        trackedProject.project().name(),
                        pos,
                        level.dimension().identifier(),
                        oldState,
                        newState
                );
                this.captureChunkBaseline(trackedProject, level, pos, oldState, oldBlockEntity, now);

                String projectId = trackedProject.project().id().toString();
                TrackedChangeBuffer buffer = this.getOrCreateBuffer(trackedProject, source, now);
                buffer.recordChange(pos, oldState, newState, oldBlockEntity, newBlockEntity, now);
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Tracked buffer {} for project {} now has {} pending changes on variant {} from base {}",
                        buffer.id(),
                        trackedProject.project().name(),
                        buffer.size(),
                        buffer.variantId(),
                        buffer.baseVersionId()
                );
                this.logBufferProgress(trackedProject.project(), buffer);
                if (buffer.isEmpty()) {
                    this.activeBuffers.remove(projectId);
                    this.dirtySessions.remove(projectId);
                    this.lastDraftFlushes.remove(projectId);
                    this.recoveryRepository.deleteDraft(trackedProject.layout());
                    LumaMod.LOGGER.info("Discarded empty active buffer for project {}", trackedProject.project().name());
                } else {
                    this.dirtySessions.add(projectId);
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture block change at {} in {}", pos, level.dimension().identifier(), exception);
        }
    }

    public void finalizeProjectSession(MinecraftServer server, String projectId) throws IOException {
        this.freezeSession(server, projectId);
    }

    public Optional<RecoveryDraft> snapshotDraft(MinecraftServer server, String projectId) throws IOException {
        TrackedChangeBuffer buffer = this.activeBuffers.get(projectId);
        if (buffer != null) {
            return buffer.isEmpty() ? Optional.empty() : Optional.of(buffer.toDraft());
        }

        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        if (trackedProject == null) {
            return Optional.empty();
        }
        return this.recoveryRepository.loadDraft(trackedProject.layout());
    }

    /**
     * Freezes the active session and keeps it durably persisted as a recovery
     * draft.
     *
     * <p>This is used before operations such as restore or variant switching
     * where capture must stop and the current pending state must survive an
     * interrupted workflow.
     */
    public Optional<TrackedChangeBuffer> freezeSession(MinecraftServer server, String projectId) throws IOException {
        TrackedChangeBuffer session = this.activeBuffers.remove(projectId);
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.remove(projectId);
        if (session == null) {
            TrackedProject trackedProject = this.findTrackedProject(server, projectId);
            if (trackedProject == null) {
                return Optional.empty();
            }
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Freezing project {} without active buffer; loading persisted draft fallback",
                    trackedProject.project().name()
            );
            return this.recoveryRepository.loadDraft(trackedProject.layout())
                    .map(draft -> TrackedChangeBuffer.fromDraft(UUID.randomUUID().toString(), draft));
        }

        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        if (trackedProject != null && !session.isEmpty()) {
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Freezing active buffer {} for project {} with {} pending changes",
                    session.id(),
                    trackedProject.project().name(),
                    session.size()
            );
            this.recoveryRepository.saveDraft(trackedProject.layout(), session.toDraft());
            LumaMod.LOGGER.info(
                    "Persisted active buffer for project {} with {} pending changes",
                    trackedProject.project().name(),
                    session.size()
            );
        }
        return session.isEmpty() ? Optional.empty() : Optional.of(session);
    }

    /**
     * Removes and returns the live tracked buffer for save operations.
     *
     * <p>Save prefers the active in-memory session so it can avoid reloading the
     * compacted recovery draft unless the client was restarted or the session was
     * already flushed out of memory.
     */
    public Optional<TrackedChangeBuffer> consumeSession(MinecraftServer server, String projectId) throws IOException {
        TrackedChangeBuffer session = this.activeBuffers.remove(projectId);
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.remove(projectId);
        if (session != null) {
            LumaMod.LOGGER.info("Consumed in-memory buffer for project {} with {} pending changes", projectId, session.size());
            TrackedProject trackedProject = this.findTrackedProject(server, projectId);
            if (trackedProject != null) {
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Consumed in-memory buffer {} for project {} with {} pending changes",
                        session.id(),
                        trackedProject.project().name(),
                        session.size()
                );
            }
            return session.isEmpty() ? Optional.empty() : Optional.of(session);
        }

        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        if (trackedProject == null) {
            return Optional.empty();
        }
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "No live buffer for project {}. Loading persisted draft for save/amend.",
                trackedProject.project().name()
        );
        return this.recoveryRepository.loadDraft(trackedProject.layout())
                .map(draft -> TrackedChangeBuffer.fromDraft(UUID.randomUUID().toString(), draft))
                .filter(buffer -> !buffer.isEmpty());
    }

    public void discardSession(MinecraftServer server, String projectId) throws IOException {
        this.activeBuffers.remove(projectId);
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.remove(projectId);
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        if (trackedProject != null) {
            this.recoveryRepository.deleteDraft(trackedProject.layout());
            LumaMod.LOGGER.info("Discarded persisted draft for project {}", trackedProject.project().name());
        }
    }

    public void flushIdleSessions(MinecraftServer server) {
        Instant now = Instant.now();
        List<String> sessionsToFinalize = new ArrayList<>();
        Map<String, Integer> idleThresholds = new HashMap<>();
        Map<String, TrackedProject> trackedProjects = new HashMap<>();

        try {
            for (TrackedProject trackedProject : this.loadTrackedProjects(server)) {
                String projectId = trackedProject.project().id().toString();
                trackedProjects.put(projectId, trackedProject);
                idleThresholds.put(projectId, trackedProject.project().settings().sessionIdleSeconds());
            }
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to load tracked projects for idle flush", exception);
        }

        for (Map.Entry<String, TrackedChangeBuffer> entry : this.activeBuffers.entrySet()) {
            String projectId = entry.getKey();
            TrackedChangeBuffer session = entry.getValue();
            int idleSeconds = idleThresholds.getOrDefault(projectId, 5);
            if (Duration.between(session.updatedAt(), now).getSeconds() >= idleSeconds) {
                TrackedProject trackedProject = trackedProjects.get(projectId);
                if (trackedProject != null) {
                    LumaDebugLog.log(
                            trackedProject.project(),
                            "capture",
                            "Idle timeout reached for project {} after {}s with {} pending changes",
                            trackedProject.project().name(),
                            idleSeconds,
                            session.size()
                    );
                }
                sessionsToFinalize.add(projectId);
                continue;
            }

            if (!this.dirtySessions.contains(projectId)) {
                continue;
            }

            Instant lastFlush = this.lastDraftFlushes.get(projectId);
            if (lastFlush != null && Duration.between(lastFlush, now).compareTo(ACTIVE_DRAFT_FLUSH_INTERVAL) < 0) {
                continue;
            }

            TrackedProject trackedProject = trackedProjects.get(projectId);
            if (trackedProject == null) {
                continue;
            }

            try {
                this.recoveryRepository.saveDraft(trackedProject.layout(), session.toDraft());
                this.dirtySessions.remove(projectId);
                this.lastDraftFlushes.put(projectId, now);
                LumaDebugLog.log(
                        trackedProject.project(),
                        "capture",
                        "Flushed live draft for project {} with {} pending changes after {}s idle",
                        trackedProject.project().name(),
                        session.size(),
                        Duration.between(session.updatedAt(), now).getSeconds()
                );
                LumaMod.LOGGER.info(
                        "Flushed active draft for project {} with {} pending changes",
                        trackedProject.project().name(),
                        session.size()
                );
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to flush active session for {}", projectId, exception);
            }
        }

        for (String projectId : sessionsToFinalize) {
            try {
                this.finalizeProjectSession(server, projectId);
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to finalize idle session for {}", projectId, exception);
            }
        }
    }

    public void flushAll(MinecraftServer server) {
        for (String projectId : List.copyOf(this.activeBuffers.keySet())) {
            try {
                this.freezeSession(server, projectId);
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to flush session for {}", projectId, exception);
            }
        }
    }

    public void invalidateProjectCache(MinecraftServer server) {
        this.projectCaches.remove(this.cacheKey(server));
    }

    private TrackedChangeBuffer getOrCreateBuffer(
            TrackedProject trackedProject,
            io.github.luma.domain.model.WorldMutationSource source,
            Instant now
    ) throws IOException {
        String projectId = trackedProject.project().id().toString();
        TrackedChangeBuffer existing = this.activeBuffers.get(projectId);
        if (existing != null) {
            return existing;
        }

        ProjectVariant activeVariant = trackedProject.variants().stream()
                .filter(variant -> variant.id().equals(trackedProject.project().activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + trackedProject.project().name()));

        Optional<RecoveryDraft> draft = this.recoveryRepository.loadDraft(trackedProject.layout());
        boolean resumedDraft = draft
                .filter(candidate -> projectId.equals(candidate.projectId()))
                .filter(candidate -> activeVariant.id().equals(candidate.variantId()))
                .isPresent();
        TrackedChangeBuffer buffer = draft
                .filter(candidate -> projectId.equals(candidate.projectId()))
                .filter(candidate -> activeVariant.id().equals(candidate.variantId()))
                .map(candidate -> TrackedChangeBuffer.fromDraft(UUID.randomUUID().toString(), candidate))
                .orElseGet(() -> TrackedChangeBuffer.create(
                        UUID.randomUUID().toString(),
                        projectId,
                        activeVariant.id(),
                        activeVariant.headVersionId(),
                        defaultActor(source),
                        source,
                        now
                ));

        this.activeBuffers.put(projectId, buffer);
        LumaMod.LOGGER.info(
                "Opened active buffer for project {} on variant {} from base {}",
                trackedProject.project().name(),
                activeVariant.id(),
                activeVariant.headVersionId()
        );
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Opened buffer {} for project {} on variant {} from base {} using {}",
                buffer.id(),
                trackedProject.project().name(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                resumedDraft ? "persisted draft" : "new session"
        );
        return buffer;
    }

    private TrackedProject findTrackedProject(MinecraftServer server, String projectId) throws IOException {
        for (TrackedProject trackedProject : this.loadTrackedProjects(server)) {
            if (trackedProject.project().id().toString().equals(projectId)) {
                return trackedProject;
            }
        }
        return null;
    }

    private boolean matches(ServerLevel level, BuildProject project, BlockPos pos) {
        if (!project.dimensionId().equals(level.dimension().identifier().toString())) {
            return false;
        }

        if (project.tracksWholeDimension() || project.bounds() == null) {
            return true;
        }

        return pos.getX() >= project.bounds().min().x()
                && pos.getX() <= project.bounds().max().x()
                && pos.getY() >= project.bounds().min().y()
                && pos.getY() <= project.bounds().max().y()
                && pos.getZ() >= project.bounds().min().z()
                && pos.getZ() <= project.bounds().max().z();
    }

    private List<TrackedProject> loadTrackedProjects(MinecraftServer server) throws IOException {
        String cacheKey = this.cacheKey(server);
        CachedProjects cachedProjects = this.projectCaches.get(cacheKey);
        if (cachedProjects != null && Duration.between(cachedProjects.loadedAt(), Instant.now()).getSeconds() < 5) {
            return cachedProjects.projects();
        }

        List<TrackedProject> trackedProjects = new ArrayList<>();
        java.nio.file.Path projectsRoot = this.projectService.projectsRoot(server);
        if (java.nio.file.Files.exists(projectsRoot)) {
            try (var stream = java.nio.file.Files.list(projectsRoot)) {
                for (var path : stream.filter(java.nio.file.Files::isDirectory).toList()) {
                    ProjectLayout layout = new ProjectLayout(path);
                    BuildProject project = this.projectRepository.load(layout).orElse(null);
                    if (project == null || project.archived()) {
                        continue;
                    }
                    trackedProjects.add(new TrackedProject(layout, project, this.variantRepository.loadAll(layout)));
                }
            }
        }

        this.projectCaches.put(cacheKey, new CachedProjects(Instant.now(), trackedProjects));
        return trackedProjects;
    }

    private List<TrackedProject> matchingProjects(ServerLevel level, BlockPos pos) throws IOException {
        List<TrackedProject> matching = new ArrayList<>();
        for (TrackedProject trackedProject : this.loadTrackedProjects(level.getServer())) {
            if (this.matches(level, trackedProject.project(), pos)) {
                matching.add(trackedProject);
            }
        }
        return matching;
    }

    private void captureChunkBaseline(
            TrackedProject trackedProject,
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            Instant now
    ) throws IOException {
        ChunkPoint chunk = new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4);
        if (this.baselineChunkRepository.contains(trackedProject.layout(), chunk)) {
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Baseline chunk {}:{} already captured for project {}",
                    chunk.x(),
                    chunk.z(),
                    trackedProject.project().name()
            );
            return;
        }

        this.baselineChunkRepository.captureIfMissing(
                trackedProject.layout(),
                trackedProject.project().id().toString(),
                chunk,
                level,
                now,
                pos,
                oldState,
                oldBlockEntity
        );
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Captured missing baseline chunk {}:{} for project {} from mutation at {}",
                chunk.x(),
                chunk.z(),
                trackedProject.project().name(),
                pos
        );
    }

    private String cacheKey(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().toString();
    }

    private void logBufferProgress(BuildProject project, TrackedChangeBuffer buffer) {
        int size = buffer.size();
        if (size == 1 || size == 64 || size == 256 || (size % 1024) == 0) {
            LumaMod.LOGGER.info(
                    "Captured {} pending changes for project {}",
                    size,
                    project.name()
            );
        }
    }

    public static boolean shouldCaptureMutation(io.github.luma.domain.model.WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER,
                    ENTITY,
                    EXPLOSION,
                    FLUID,
                    FIRE,
                    GROWTH,
                    BLOCK_UPDATE,
                    PISTON,
                    FALLING_BLOCK,
                    EXPLOSIVE,
                    MOB,
                    EXTERNAL_TOOL -> true;
            case RESTORE, SYSTEM -> false;
        };
    }

    public static String defaultActor(io.github.luma.domain.model.WorldMutationSource source) {
        if (source == null) {
            return "world";
        }
        return switch (source) {
            case PLAYER -> "player";
            case ENTITY -> "entity";
            case EXPLOSION -> "explosion";
            case FLUID -> "fluid";
            case FIRE -> "fire";
            case GROWTH -> "growth";
            case BLOCK_UPDATE -> "block-update";
            case PISTON -> "piston";
            case FALLING_BLOCK -> "falling-block";
            case EXPLOSIVE -> "explosive";
            case MOB -> "mob";
            case EXTERNAL_TOOL -> "external-tool";
            case RESTORE, SYSTEM -> "world";
        };
    }

    private record TrackedProject(ProjectLayout layout, BuildProject project, List<ProjectVariant> variants) {
    }

    private record CachedProjects(Instant loadedAt, List<TrackedProject> projects) {
    }
}
