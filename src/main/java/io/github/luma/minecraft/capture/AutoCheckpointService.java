package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.VersionService;
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
    private static final int LARGE_COMMAND_THRESHOLD = 512;
    private static final int DEDUP_LIMIT = 256;

    private final AutoCheckpointCommandClassifier commandClassifier =
            new AutoCheckpointCommandClassifier(LARGE_COMMAND_THRESHOLD);
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
        if (!this.commandClassifier.shouldCheckpoint(command, player.blockPosition())) {
            return;
        }
        this.checkpoint(level, "command:" + WorldMutationContext.currentActionId(), player.getName().getString(), "large command");
    }

    public void checkpointBeforeExternalOperation(
            ServerLevel level,
            WorldMutationSource source,
            String actor,
            String actionId
    ) {
        if (level == null || source == null) {
            return;
        }
        this.checkpoint(level, source.name().toLowerCase(java.util.Locale.ROOT) + ":" + actionId, actor, source.name());
    }

    private void checkpoint(ServerLevel level, String dedupKey, String author, String reason) {
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
            Optional<BuildProject> project = this.projectService.findWorldProject(level);
            if (project.isEmpty()) {
                return;
            }
            if (!project.get().settings().autoCheckpointEnabled()) {
                return;
            }
            Optional<RecoveryDraft> draft = this.recoveryService.loadDraft(level.getServer(), project.get().name());
            if (draft.isEmpty() || draft.get().isEmpty()) {
                return;
            }
            if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
                LumaMod.LOGGER.info("Skipped auto checkpoint for {} because a Lumi operation is active", project.get().name());
                return;
            }
            this.versionService.startSaveVersion(
                    level,
                    project.get().name(),
                    "Auto checkpoint before " + reason,
                    author == null || author.isBlank() ? "lumi" : author,
                    VersionKind.AUTO_CHECKPOINT
            );
            LumaMod.LOGGER.info("Queued auto checkpoint for project {} before {}", project.get().name(), reason);
        } catch (IOException | RuntimeException exception) {
            LumaMod.LOGGER.warn("Auto checkpoint failed before {}", reason, exception);
        }
    }
}
