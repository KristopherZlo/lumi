package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeSession;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.minecraft.world.BlockStateNbtCodec;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class HistoryCaptureManager {

    private static final HistoryCaptureManager INSTANCE = new HistoryCaptureManager();

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final RecoveryService recoveryService = new RecoveryService();
    private final Map<String, ChangeSession> activeSessions = new HashMap<>();
    private final Map<String, CachedProjects> projectCaches = new HashMap<>();

    private HistoryCaptureManager() {
    }

    public static HistoryCaptureManager getInstance() {
        return INSTANCE;
    }

    public void recordBlockChange(
            ServerLevel level,
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity
    ) {
        if (WorldMutationContext.currentSource() != io.github.luma.domain.model.WorldMutationSource.PLAYER) {
            return;
        }

        try {
            Instant now = Instant.now();
            for (TrackedProject trackedProject : this.loadTrackedProjects(level.getServer())) {
                if (!this.matches(level, trackedProject.project(), pos)) {
                    continue;
                }

                ChangeSession session = this.getOrCreateSession(level.getServer(), trackedProject, now);
                BlockChangeRecord change = new BlockChangeRecord(
                        BlockPoint.from(pos),
                        BlockStateNbtCodec.serializeBlockState(oldState),
                        BlockStateNbtCodec.serializeBlockState(newState),
                        BlockStateNbtCodec.serializeBlockEntity(oldBlockEntity),
                        BlockStateNbtCodec.serializeBlockEntity(newBlockEntity)
                );
                ChangeSession nextSession = session.addChange(change, now);

                if (nextSession.isEmpty()) {
                    this.activeSessions.remove(trackedProject.project().id().toString());
                    this.recoveryRepository.deleteDraft(trackedProject.layout());
                } else {
                    this.activeSessions.put(trackedProject.project().id().toString(), nextSession);
                    this.recoveryService.saveSessionDraft(trackedProject.layout(), nextSession);
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to capture block change", exception);
        }
    }

    public void finalizeProjectSession(MinecraftServer server, String projectId) throws IOException {
        ChangeSession session = this.activeSessions.remove(projectId);
        if (session == null) {
            return;
        }

        for (TrackedProject trackedProject : this.loadTrackedProjects(server)) {
            if (trackedProject.project().id().toString().equals(projectId) && !session.isEmpty()) {
                this.recoveryService.saveSessionDraft(trackedProject.layout(), session);
            }
        }
    }

    public void flushIdleSessions(MinecraftServer server) {
        Instant now = Instant.now();
        List<String> sessionsToFinalize = new ArrayList<>();

        for (Map.Entry<String, ChangeSession> entry : this.activeSessions.entrySet()) {
            if (Duration.between(entry.getValue().updatedAt(), now).getSeconds() >= 5) {
                sessionsToFinalize.add(entry.getKey());
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
        for (String projectId : List.copyOf(this.activeSessions.keySet())) {
            try {
                this.finalizeProjectSession(server, projectId);
            } catch (IOException exception) {
                LumaMod.LOGGER.warn("Failed to flush session for {}", projectId, exception);
            }
        }
    }

    public void invalidateProjectCache(MinecraftServer server) {
        this.projectCaches.remove(this.cacheKey(server));
    }

    private ChangeSession getOrCreateSession(MinecraftServer server, TrackedProject trackedProject, Instant now) throws IOException {
        String projectId = trackedProject.project().id().toString();
        ChangeSession existing = this.activeSessions.get(projectId);
        if (existing != null) {
            return existing;
        }

        ProjectVariant activeVariant = trackedProject.variants().stream()
                .filter(variant -> variant.id().equals(trackedProject.project().activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + trackedProject.project().name()));

        ChangeSession session = ChangeSession.create(
                UUID.randomUUID().toString(),
                projectId,
                activeVariant.id(),
                activeVariant.headVersionId(),
                "player",
                io.github.luma.domain.model.WorldMutationSource.PLAYER,
                now
        );

        var draft = this.recoveryRepository.loadDraft(trackedProject.layout());
        if (draft.isPresent()) {
            session = this.recoveryService.mergeDraft(session, draft.get());
        }

        this.activeSessions.put(projectId, session);
        return session;
    }

    private boolean matches(ServerLevel level, BuildProject project, BlockPos pos) {
        if (!project.dimensionId().equals(level.dimension().identifier().toString())) {
            return false;
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

    private String cacheKey(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().toString();
    }

    private record TrackedProject(ProjectLayout layout, BuildProject project, List<ProjectVariant> variants) {
    }

    private record CachedProjects(Instant loadedAt, List<TrackedProject> projects) {
    }
}
