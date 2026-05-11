package io.github.luma.gametest;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Creates the real Lumi world workspace and commits captured stress changes.
 */
final class LumiBackupStressHistorySaveFlow {

    private static final int HISTORY_SAVE_TIMEOUT_TICKS = 20 * 600;

    private final String actor;
    private final int expectedBlocks;
    private final int expectedChunks;
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    LumiBackupStressHistorySaveFlow(String actor, int expectedBlocks, int expectedChunks) {
        this.actor = actor == null || actor.isBlank() ? "Lumi backup stress" : actor;
        this.expectedBlocks = expectedBlocks;
        this.expectedChunks = expectedChunks;
    }

    BuildProject createWorldWorkspace(
            TestSingleplayerContext singleplayer,
            LumiBackupStressMetrics metrics
    ) throws Exception {
        long startedAt = System.nanoTime();
        BuildProject project = singleplayer.getServer().computeOnServer(server ->
                this.projectService.ensureWorldProject(server.overworld(), this.actor));
        metrics.worldProjectCreateMs = elapsedMillis(startedAt);
        metrics.projectName = project.name();
        LumaMod.LOGGER.info(
                "Lumi backup stress world workspace ready: project={} durationMs={}",
                project.name(),
                metrics.worldProjectCreateMs
        );
        return project;
    }

    void commitHistorySave(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            BuildProject project,
            LumiBackupStressMetrics metrics
    ) throws Exception {
        long startedAt = System.nanoTime();
        OperationHandle handle = singleplayer.getServer().computeOnServer(server ->
                this.versionService.startSaveVersion(
                        server.overworld(),
                        project.name(),
                        "Backup stress 100k modification",
                        this.actor
                ));
        OperationSnapshot snapshot = this.waitForOperation(context, singleplayer, handle);
        metrics.historySaveMs = elapsedMillis(startedAt);
        if (snapshot.failed()) {
            throw new AssertionError("Lumi history save failed: " + snapshot.detail());
        }

        ProjectVersion savedVersion = singleplayer.getServer().computeOnServer(server -> {
            List<ProjectVersion> versions = this.projectService.loadVersions(server, project.name());
            if (versions.size() < 2) {
                throw new IOException("Lumi history save did not create a committed version");
            }
            return versions.get(versions.size() - 1);
        });
        this.recordAndValidateSavedVersion(savedVersion, metrics);
    }

    private void recordAndValidateSavedVersion(
            ProjectVersion savedVersion,
            LumiBackupStressMetrics metrics
    ) throws IOException {
        metrics.historySavedBlocks = savedVersion.stats().changedBlocks();
        metrics.historySavedChunks = savedVersion.stats().changedChunks();
        metrics.historyPatchCount = savedVersion.patchIds().size();
        if (metrics.historySavedBlocks < this.expectedBlocks) {
            throw new IOException("Lumi history save captured too few blocks: " + metrics.historySavedBlocks
                    + "/" + this.expectedBlocks);
        }
        if (metrics.historySavedChunks < this.expectedChunks) {
            throw new IOException("Lumi history save captured too few chunks: " + metrics.historySavedChunks
                    + "/" + this.expectedChunks);
        }
        if (metrics.historyPatchCount < 1) {
            throw new IOException("Lumi history save did not write a patch payload");
        }
        LumaMod.LOGGER.info(
                "Lumi backup stress history save complete: version={} blocks={} chunks={} patches={} durationMs={}",
                savedVersion.id(),
                metrics.historySavedBlocks,
                metrics.historySavedChunks,
                metrics.historyPatchCount,
                metrics.historySaveMs
        );
    }

    private OperationSnapshot waitForOperation(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            OperationHandle handle
    ) throws Exception {
        OperationSnapshot lastSnapshot = null;
        for (int tick = 0; tick < HISTORY_SAVE_TIMEOUT_TICKS; tick++) {
            OperationWaitState state = singleplayer.getServer().computeOnServer(server -> new OperationWaitState(
                    this.worldOperationManager.snapshot(server, handle).orElse(null),
                    this.worldOperationManager.hasActiveOperation(server)
            ));
            if (state.snapshot() != null) {
                lastSnapshot = state.snapshot();
            }
            if (state.snapshot() != null && state.snapshot().terminal() && !state.active()) {
                return state.snapshot();
            }
            context.waitTick();
        }
        String detail = lastSnapshot == null ? "no operation snapshot" : lastSnapshot.stage() + " " + lastSnapshot.detail();
        throw new AssertionError("Timed out waiting for Lumi history save operation: " + detail);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private record OperationWaitState(OperationSnapshot snapshot, boolean active) {
    }
}
