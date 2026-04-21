package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.CaptureSessionState;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
    private static final int SECONDARY_SOURCE_JOIN_RADIUS = 2;
    private static final int STARTUP_CAPTURE_TRACE_LIMIT = 32;
    private static final int CAPTURE_SUMMARY_ENTRY_LIMIT = 4;

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final SessionStabilizationService stabilizationService = new SessionStabilizationService();
    private final Map<String, TrackedChangeBuffer> activeBuffers = new HashMap<>();
    private final Map<String, CaptureSessionState> activeSessions = new HashMap<>();
    private final Map<String, CaptureSessionDiagnostics> sessionDiagnostics = new HashMap<>();
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
                if (!allowsAutomaticProjectCreation(source)) {
                    LumaDebugLog.log(
                            "capture",
                            "Skipped {} mutation at {} in {} because no tracked workspace exists and the source cannot bootstrap one",
                            source,
                            pos,
                            level.dimension().identifier()
                    );
                    return;
                }
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
                if (!this.canCaptureIntoSession(trackedProject, source, pos)) {
                    continue;
                }
                if (!this.ensureTrackedChunk(trackedProject, level, pos, oldState, oldBlockEntity, source, now)) {
                    continue;
                }
                String projectId = trackedProject.project().id().toString();
                TrackedChangeBuffer buffer = this.getOrCreateBuffer(trackedProject, source, now);
                CaptureSessionState session = this.activeSessions.get(projectId);
                if (session == null) {
                    continue;
                }
                ChunkPoint chunk = ChunkPoint.from(pos);
                if (this.isExplicitRootSource(source)) {
                    this.captureSessionEnvelopeBaseline(trackedProject, level, session, chunk, pos, oldState, oldBlockEntity);
                    session.addRootChunk(chunk);
                } else if (this.usesDeferredStabilization(trackedProject.project(), source)) {
                    if (!session.isWithinStabilizationEnvelope(chunk)) {
                        LumaDebugLog.log(
                                trackedProject.project(),
                                "capture",
                                "Skipped deferred {} mutation at {} for project {} because chunk {}:{} is outside the causal envelope",
                                source,
                                pos,
                                trackedProject.project().name(),
                                chunk.x(),
                                chunk.z()
                        );
                        continue;
                    }
                    session.markEnvelopeChunkDirty(chunk);
                    this.dirtySessions.add(projectId);
                    LumaDebugLog.log(
                            trackedProject.project(),
                            "capture",
                            "Marked chunk {}:{} dirty for deferred {} stabilization in project {}",
                            chunk.x(),
                            chunk.z(),
                            source,
                            trackedProject.project().name()
                    );
                    continue;
                } else if (session.isWithinStabilizationEnvelope(chunk)) {
                    session.markEnvelopeChunkDirty(chunk);
                }
                int pendingBefore = buffer.size();
                buffer.recordChange(pos, oldState, newState, oldBlockEntity, newBlockEntity, now);
                int pendingAfter = buffer.size();
                CaptureSessionDiagnostics diagnostics = this.diagnosticsForSession(projectId);
                diagnostics.record(source, pos, oldState, newState, oldBlockEntity != null, newBlockEntity != null);
                this.logAcceptedCaptureTrace(trackedProject.project(), buffer, diagnostics, pendingBefore, pendingAfter);
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
                this.logBufferProgress(trackedProject.project(), buffer, diagnostics);
                if (buffer.isEmpty()) {
                    this.activeBuffers.remove(projectId);
                    this.activeSessions.remove(projectId);
                    this.dirtySessions.remove(projectId);
                    this.lastDraftFlushes.remove(projectId);
                    this.clearSessionDiagnostics(projectId);
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
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.activeSessions.get(projectId);
        TrackedChangeBuffer buffer = this.activeBuffers.get(projectId);
        if (buffer != null) {
            if (trackedProject != null && sessionState != null) {
                this.reconcileSession(server, trackedProject, sessionState);
            }
            return buffer.isEmpty() ? Optional.empty() : Optional.of(buffer.toDraft());
        }
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
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.activeSessions.get(projectId);
        if (trackedProject != null && sessionState != null) {
            this.reconcileSession(server, trackedProject, sessionState);
        }
        TrackedChangeBuffer session = this.activeBuffers.remove(projectId);
        this.activeSessions.remove(projectId);
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.remove(projectId);
        this.clearSessionDiagnostics(projectId);
        if (session == null) {
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
        TrackedProject trackedProject = this.findTrackedProject(server, projectId);
        CaptureSessionState sessionState = this.activeSessions.get(projectId);
        if (trackedProject != null && sessionState != null) {
            this.reconcileSession(server, trackedProject, sessionState);
        }
        TrackedChangeBuffer session = this.activeBuffers.remove(projectId);
        this.activeSessions.remove(projectId);
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.remove(projectId);
        this.clearSessionDiagnostics(projectId);
        if (session != null) {
            LumaMod.LOGGER.info("Consumed in-memory buffer for project {} with {} pending changes", projectId, session.size());
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
        this.activeSessions.remove(projectId);
        this.dirtySessions.remove(projectId);
        this.lastDraftFlushes.remove(projectId);
        this.clearSessionDiagnostics(projectId);
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

        for (Map.Entry<String, TrackedChangeBuffer> entry : List.copyOf(this.activeBuffers.entrySet())) {
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
                CaptureSessionState sessionState = this.activeSessions.get(projectId);
                if (trackedProject != null && sessionState != null) {
                    this.reconcileSession(server, trackedProject, sessionState);
                }
                if (!this.activeBuffers.containsKey(projectId) || session.isEmpty()) {
                    continue;
                }
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
        CaptureSessionDiagnostics diagnostics = this.diagnosticsForSession(projectId);
        if (existing != null) {
            this.activeSessions.computeIfAbsent(projectId, ignored -> CaptureSessionState.create(existing));
            diagnostics.seedFromBuffer(existing);
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
        this.activeSessions.put(projectId, resumedDraft ? CaptureSessionState.resume(buffer) : CaptureSessionState.create(buffer));
        diagnostics.seedFromBuffer(buffer);
        LumaMod.LOGGER.info(
                "Opened active buffer for project {} on variant {} from base {} using {} source",
                trackedProject.project().name(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                source
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

    private boolean ensureTrackedChunk(
            TrackedProject trackedProject,
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            io.github.luma.domain.model.WorldMutationSource source,
            Instant now
    ) throws IOException {
        ChunkPoint chunk = new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4);
        CaptureSessionState session = this.activeSessions.get(trackedProject.project().id().toString());
        if (session != null && session.hasBaselineChunk(chunk)) {
            return true;
        }
        if (this.baselineChunkRepository.contains(trackedProject.layout(), chunk)) {
            return true;
        }
        if (!allowsTrackedChunkExpansion(source)) {
            LumaDebugLog.log(
                    trackedProject.project(),
                    "capture",
                    "Skipped {} mutation at {} because chunk {}:{} is not tracked yet and the source cannot expand tracking",
                    source,
                    pos,
                    chunk.x(),
                    chunk.z()
            );
            return false;
        }

        this.captureChunkBaseline(trackedProject, level, pos, oldState, oldBlockEntity, now);
        return true;
    }

    private boolean canCaptureIntoSession(
            TrackedProject trackedProject,
            io.github.luma.domain.model.WorldMutationSource source,
            BlockPos pos
    ) {
        String projectId = trackedProject.project().id().toString();
        if (this.activeBuffers.containsKey(projectId)) {
            if (!requiresActiveRegionMembership(source)) {
                return true;
            }
            ChunkPoint chunk = ChunkPoint.from(pos);
            CaptureSessionState sessionState = this.activeSessions.get(projectId);
            if (sessionState != null && sessionState.isWithinStabilizationEnvelope(chunk)) {
                return true;
            }
            LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                    "Skipped {} mutation at {} for project {} because chunk {}:{} is outside the active session region",
                    source,
                    pos,
                    trackedProject.project().name(),
                    chunk.x(),
                    chunk.z()
            );
            return false;
        }
        if (allowsSessionBootstrap(source)) {
            return true;
        }
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Skipped {} mutation at {} for project {} because no active session exists and the source cannot bootstrap capture",
                source,
                pos,
                trackedProject.project().name()
        );
        return false;
    }

    private String cacheKey(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().toString();
    }

    private CaptureSessionDiagnostics diagnosticsForSession(String projectId) {
        return this.sessionDiagnostics.computeIfAbsent(projectId, ignored -> new CaptureSessionDiagnostics());
    }

    private void clearSessionDiagnostics(String projectId) {
        this.sessionDiagnostics.remove(projectId);
    }

    private boolean isExplicitRootSource(io.github.luma.domain.model.WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER, ENTITY, EXPLOSIVE, EXTERNAL_TOOL -> true;
            case EXPLOSION, FLUID, FIRE, GROWTH, BLOCK_UPDATE, PISTON, FALLING_BLOCK, MOB, RESTORE, SYSTEM -> false;
        };
    }

    private boolean usesDeferredStabilization(BuildProject project, io.github.luma.domain.model.WorldMutationSource source) {
        if (project == null || source == null) {
            return false;
        }
        return project.tracksWholeDimension()
                && (source == io.github.luma.domain.model.WorldMutationSource.FLUID
                || source == io.github.luma.domain.model.WorldMutationSource.FALLING_BLOCK);
    }

    private void captureSessionEnvelopeBaseline(
            TrackedProject trackedProject,
            ServerLevel level,
            CaptureSessionState session,
            ChunkPoint rootChunk,
            BlockPos changedPos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) {
        for (int offsetX = -CaptureSessionState.STABILIZATION_HALO_RADIUS; offsetX <= CaptureSessionState.STABILIZATION_HALO_RADIUS; offsetX++) {
            for (int offsetZ = -CaptureSessionState.STABILIZATION_HALO_RADIUS; offsetZ <= CaptureSessionState.STABILIZATION_HALO_RADIUS; offsetZ++) {
                ChunkPoint chunk = new ChunkPoint(rootChunk.x() + offsetX, rootChunk.z() + offsetZ);
                if (session.hasBaselineChunk(chunk)) {
                    continue;
                }
                BlockPos overridePos = chunk.equals(rootChunk) ? changedPos : null;
                BlockState overrideState = chunk.equals(rootChunk) ? oldState : null;
                CompoundTag overrideTag = chunk.equals(rootChunk) ? oldBlockEntity : null;
                session.captureBaselineChunk(
                        chunk,
                        this.stabilizationService.captureBaselineChunkState(
                                level,
                                trackedProject.project(),
                                chunk,
                                overridePos,
                                overrideState,
                                overrideTag
                        )
                );
            }
        }
    }

    private void reconcileSession(
            MinecraftServer server,
            TrackedProject trackedProject,
            CaptureSessionState session
    ) throws IOException {
        ServerLevel level = this.resolveProjectLevel(server, trackedProject.project());
        if (level == null) {
            return;
        }
        SessionStabilizationService.ReconciliationResult result = this.stabilizationService.stabilizePendingChunks(
                level,
                trackedProject.project(),
                session
        );
        if (result.inFlight() || result.chunkCount() <= 0) {
            return;
        }
        String projectId = trackedProject.project().id().toString();
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Reconciled {} dirty chunks for project {}: delta={} composed={} buffer {} -> {}",
                result.chunkCount(),
                trackedProject.project().name(),
                result.deltaChangeCount(),
                result.composedChangeCount(),
                result.bufferBefore(),
                result.bufferAfter()
        );
        LumaMod.LOGGER.info(
                "Reconciled {} dirty chunks for project {}: delta={} composed={} buffer {} -> {}",
                result.chunkCount(),
                trackedProject.project().name(),
                result.deltaChangeCount(),
                result.composedChangeCount(),
                result.bufferBefore(),
                result.bufferAfter()
        );
        if (session.buffer().isEmpty()) {
            this.activeBuffers.remove(projectId);
            this.activeSessions.remove(projectId);
            this.dirtySessions.remove(projectId);
            this.lastDraftFlushes.remove(projectId);
            this.clearSessionDiagnostics(projectId);
            this.recoveryRepository.deleteDraft(trackedProject.layout());
            LumaMod.LOGGER.info("Discarded empty active buffer for project {} after reconciliation", trackedProject.project().name());
        }
    }

    private ServerLevel resolveProjectLevel(MinecraftServer server, BuildProject project) {
        if (server == null || project == null || project.dimensionId() == null || project.dimensionId().isBlank()) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (project.dimensionId().equals(level.dimension().identifier().toString())) {
                return level;
            }
        }
        return null;
    }

    private void logAcceptedCaptureTrace(
            BuildProject project,
            TrackedChangeBuffer buffer,
            CaptureSessionDiagnostics diagnostics,
            int pendingBefore,
            int pendingAfter
    ) {
        int accepted = diagnostics.acceptedMutations();
        if (accepted <= STARTUP_CAPTURE_TRACE_LIMIT) {
            LumaMod.LOGGER.info(
                    "Capture trace {}/{} for project {}: source={} sessionSource={} pos={} chunk={}:{} {} -> {} oldBe={} newBe={} pending={} delta={}",
                    accepted,
                    STARTUP_CAPTURE_TRACE_LIMIT,
                    project.name(),
                    diagnostics.lastSource(),
                    buffer.mutationSource(),
                    this.formatPos(diagnostics.lastPos()),
                    diagnostics.lastChunk().x(),
                    diagnostics.lastChunk().z(),
                    diagnostics.lastOldBlockId(),
                    diagnostics.lastNewBlockId(),
                    diagnostics.lastOldBlockEntity(),
                    diagnostics.lastNewBlockEntity(),
                    pendingAfter,
                    pendingAfter - pendingBefore
            );
            if (accepted == STARTUP_CAPTURE_TRACE_LIMIT) {
                LumaMod.LOGGER.info(
                        "Capture trace limit reached for project {}. Further accepted mutations in this session will be summarized only at progress checkpoints.",
                        project.name()
                );
            }
        }
    }

    private void logBufferProgress(BuildProject project, TrackedChangeBuffer buffer, CaptureSessionDiagnostics diagnostics) {
        int size = buffer.size();
        if (size == 1 || size == 64 || size == 256 || (size % 1024) == 0) {
            LumaMod.LOGGER.info(
                    "Captured {} pending changes for project {} (accepted={} sources=[{}] transitions=[{}] last={} source={} chunk={}:{})",
                    size,
                    project.name(),
                    diagnostics.acceptedMutations(),
                    diagnostics.describeTopSources(CAPTURE_SUMMARY_ENTRY_LIMIT),
                    diagnostics.describeTopTransitions(CAPTURE_SUMMARY_ENTRY_LIMIT),
                    this.formatPos(diagnostics.lastPos()),
                    diagnostics.lastSource(),
                    diagnostics.lastChunk().x(),
                    diagnostics.lastChunk().z()
            );
        }
    }

    private String formatPos(BlockPos pos) {
        if (pos == null) {
            return "unknown";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String blockId(BlockState state) {
        if (state == null) {
            return "minecraft:air";
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
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

    public static boolean allowsAutomaticProjectCreation(io.github.luma.domain.model.WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER,
                    ENTITY,
                    EXPLOSIVE,
                    EXTERNAL_TOOL -> true;
            case EXPLOSION,
                    FLUID,
                    FIRE,
                    GROWTH,
                    BLOCK_UPDATE,
                    PISTON,
                    FALLING_BLOCK,
                    MOB,
                    RESTORE,
                    SYSTEM -> false;
        };
    }

    public static boolean allowsSessionBootstrap(io.github.luma.domain.model.WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER,
                    ENTITY,
                    EXPLOSIVE,
                    EXTERNAL_TOOL -> true;
            case EXPLOSION,
                    FLUID,
                    FIRE,
                    GROWTH,
                    BLOCK_UPDATE,
                    PISTON,
                    FALLING_BLOCK,
                    MOB,
                    RESTORE,
                    SYSTEM -> false;
        };
    }

    public static boolean allowsTrackedChunkExpansion(io.github.luma.domain.model.WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case PLAYER,
                    ENTITY,
                    EXPLOSION,
                    FIRE,
                    GROWTH,
                    BLOCK_UPDATE,
                    PISTON,
                    MOB,
                    EXPLOSIVE,
                    EXTERNAL_TOOL -> true;
            case FLUID,
                    FALLING_BLOCK -> false;
            case RESTORE, SYSTEM -> false;
        };
    }

    static boolean requiresActiveRegionMembership(io.github.luma.domain.model.WorldMutationSource source) {
        if (source == null) {
            return false;
        }
        return switch (source) {
            case EXPLOSION,
                    FLUID,
                    FIRE,
                    GROWTH,
                    BLOCK_UPDATE,
                    PISTON,
                    FALLING_BLOCK,
                    MOB -> true;
            case PLAYER,
                    ENTITY,
                    EXPLOSIVE,
                    EXTERNAL_TOOL,
                    RESTORE,
                    SYSTEM -> false;
        };
    }

    static boolean isWithinChunkRadius(ChunkPoint first, ChunkPoint second, int radius) {
        if (first == null || second == null || radius < 0) {
            return false;
        }
        return Math.abs(first.x() - second.x()) <= radius
                && Math.abs(first.z() - second.z()) <= radius;
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

    private static final class CaptureSessionDiagnostics {

        private int acceptedMutations;
        private final Map<String, ChunkPoint> activeChunks = new LinkedHashMap<>();
        private final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        private final Map<String, Integer> transitionCounts = new LinkedHashMap<>();
        private BlockPos lastPos;
        private ChunkPoint lastChunk = new ChunkPoint(0, 0);
        private String lastSource = "unknown";
        private String lastOldBlockId = "minecraft:air";
        private String lastNewBlockId = "minecraft:air";
        private boolean lastOldBlockEntity;
        private boolean lastNewBlockEntity;

        private void record(
                io.github.luma.domain.model.WorldMutationSource source,
                BlockPos pos,
                BlockState oldState,
                BlockState newState,
                boolean oldBlockEntity,
                boolean newBlockEntity
        ) {
            this.acceptedMutations += 1;
            String sourceKey = source == null ? "unknown" : source.name();
            String transitionKey = blockId(oldState) + " -> " + blockId(newState);
            this.sourceCounts.merge(sourceKey, 1, Integer::sum);
            this.transitionCounts.merge(transitionKey, 1, Integer::sum);
            this.lastPos = pos == null ? null : pos.immutable();
            this.lastChunk = pos == null ? new ChunkPoint(0, 0) : new ChunkPoint(pos.getX() >> 4, pos.getZ() >> 4);
            this.addActiveChunk(this.lastChunk);
            this.lastSource = sourceKey;
            this.lastOldBlockId = blockId(oldState);
            this.lastNewBlockId = blockId(newState);
            this.lastOldBlockEntity = oldBlockEntity;
            this.lastNewBlockEntity = newBlockEntity;
        }

        private void seedFromBuffer(TrackedChangeBuffer buffer) {
            if (!this.activeChunks.isEmpty() || buffer == null || buffer.isEmpty()) {
                return;
            }
            for (var change : buffer.orderedChanges()) {
                this.addActiveChunk(ChunkPoint.from(change.pos()));
            }
        }

        private boolean isWithinActiveRegion(ChunkPoint chunk, int radius) {
            if (chunk == null || this.activeChunks.isEmpty()) {
                return false;
            }
            for (int chunkX = chunk.x() - radius; chunkX <= chunk.x() + radius; chunkX++) {
                for (int chunkZ = chunk.z() - radius; chunkZ <= chunk.z() + radius; chunkZ++) {
                    if (this.activeChunks.containsKey(key(new ChunkPoint(chunkX, chunkZ)))) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void addActiveChunk(ChunkPoint chunk) {
            this.activeChunks.putIfAbsent(key(chunk), chunk);
        }

        private int acceptedMutations() {
            return this.acceptedMutations;
        }

        private String lastSource() {
            return this.lastSource;
        }

        private BlockPos lastPos() {
            return this.lastPos;
        }

        private ChunkPoint lastChunk() {
            return this.lastChunk;
        }

        private String lastOldBlockId() {
            return this.lastOldBlockId;
        }

        private String lastNewBlockId() {
            return this.lastNewBlockId;
        }

        private boolean lastOldBlockEntity() {
            return this.lastOldBlockEntity;
        }

        private boolean lastNewBlockEntity() {
            return this.lastNewBlockEntity;
        }

        private String describeTopSources(int limit) {
            return this.describeTopCounts(this.sourceCounts, limit);
        }

        private String describeTopTransitions(int limit) {
            return this.describeTopCounts(this.transitionCounts, limit);
        }

        private String describeTopCounts(Map<String, Integer> counts, int limit) {
            if (counts.isEmpty()) {
                return "none";
            }
            return counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("none");
        }

        private static String key(ChunkPoint chunk) {
            return chunk.x() + ":" + chunk.z();
        }
    }
}
