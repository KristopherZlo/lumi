package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Saves pending drafts before large external or command-driven edits begin.
 */
public final class AutoCheckpointService {

    private static final AutoCheckpointService INSTANCE = new AutoCheckpointService();
    private static final int DEDUP_LIMIT = 256;

    private final ProjectService projectService = new ProjectService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final VersionService versionService = new VersionService();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final Map<String, Boolean> checkpointedActions = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return this.size() > DEDUP_LIMIT;
        }
    });

    private AutoCheckpointService() {
    }

    public static AutoCheckpointService getInstance() {
        return INSTANCE;
    }

    public void checkpointBeforeCommand(ServerPlayer player, String command) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!LumaAccessControl.getInstance().canUse(player)) {
            return;
        }
        Optional<BuildProject> project = this.findProject(level, "large command");
        if (project.isEmpty() || !project.get().settings().autoCheckpointEnabled()) {
            return;
        }
        int threshold = project.get().settings().autoCheckpointLargeChangeThreshold();
        if (!new AutoCheckpointCommandClassifier(threshold).shouldCheckpoint(command, player.blockPosition())) {
            return;
        }
        this.checkpoint(level, project.get(), "command:" + WorldMutationContext.currentActionId(), player.getName().getString(), "large command");
    }

    public void checkpointBeforeExternalOperation(
            ServerLevel level,
            WorldMutationSource source,
            String actor,
            String actionId
    ) {
        this.checkpointBeforeExternalOperation(level, source, actor, actionId, false);
    }

    public void checkpointBeforeExternalOperation(
            ServerLevel level,
            WorldMutationSource source,
            String actor,
            String actionId,
            boolean accessAllowed
    ) {
        if (level == null || source == null) {
            return;
        }
        if (!accessAllowed) {
            return;
        }
        this.findProject(level, source.name()).ifPresent(project ->
                this.checkpoint(level, project, source.name().toLowerCase(java.util.Locale.ROOT) + ":" + actionId, actor, source.name()));
    }

    private Optional<BuildProject> findProject(ServerLevel level, String reason) {
        try {
            return this.projectService.findWorldProject(level);
        } catch (IOException | RuntimeException exception) {
            LumaMod.LOGGER.warn("Auto checkpoint failed before {}", reason, exception);
            return Optional.empty();
        }
    }

    private void checkpoint(ServerLevel level, BuildProject project, String dedupKey, String author, String reason) {
        if (!project.settings().autoCheckpointEnabled()) {
            return;
        }
        if (dedupKey == null || dedupKey.isBlank()) {
            dedupKey = reason + ":" + System.nanoTime();
        }
        synchronized (this.checkpointedActions) {
            if (this.checkpointedActions.containsKey(dedupKey)) {
                return;
            }
            this.checkpointedActions.put(dedupKey, true);
        }

        try {
            Optional<RecoveryDraft> draft = this.recoveryService.loadDraft(level.getServer(), project.name());
            if (draft.isEmpty() || draft.get().isEmpty()) {
                return;
            }
            if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
                LumaMod.LOGGER.info("Skipped auto checkpoint for {} because a Lumi operation is active", project.name());
                return;
            }
            this.versionService.startSaveVersion(
                    level,
                    project.name(),
                    "Auto checkpoint before " + reason,
                    author == null || author.isBlank() ? "lumi" : author,
                    VersionKind.AUTO_CHECKPOINT
            );
            LumaMod.LOGGER.info("Queued auto checkpoint for project {} before {}", project.name(), reason);
        } catch (IOException | RuntimeException exception) {
            LumaMod.LOGGER.warn("Auto checkpoint failed before {}", reason, exception);
        }
    }
}
