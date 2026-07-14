package io.github.luma.minecraft.bootstrap;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/**
 * Runs startup-only project metadata bootstrap away from the server start path.
 */
public final class WorldBootstrapService implements AutoCloseable {

    private static final String OPERATION_ID = "world-bootstrap";

    private final ProjectService projectService;
    private final WorldOperationManager worldOperationManager;
    private final WorldBootstrapDelay delay = new WorldBootstrapDelay();
    private MinecraftServer scheduledServer;

    public WorldBootstrapService() {
        this(new ProjectService(), WorldOperationManager.getInstance());
    }

    WorldBootstrapService(ProjectService projectService, WorldOperationManager worldOperationManager) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.worldOperationManager = Objects.requireNonNull(worldOperationManager, "worldOperationManager");
    }

    public void bootstrap(MinecraftServer server) {
        if (server == null) {
            return;
        }
        this.scheduledServer = server;
        this.delay.reset();
    }

    public void tick(MinecraftServer server) {
        if (server == null || this.scheduledServer != server) {
            return;
        }
        if (server.getPlayerList().getPlayerCount() <= 0) {
            return;
        }
        if (!this.delay.tick(this.chunkLoadingActive(server))
                || this.worldOperationManager.hasActiveOperation(server)) {
            return;
        }

        this.scheduledServer = null;
        this.worldOperationManager.startBackgroundOperation(
                server.overworld(),
                OPERATION_ID,
                OPERATION_ID,
                "steps",
                false,
                progress -> this.bootstrapNow(server, progress)
        );
    }

    @Override
    public void close() {
        this.scheduledServer = null;
        this.delay.reset();
    }

    private void bootstrapNow(MinecraftServer server, WorldOperationManager.ProgressSink progress) throws Exception {
        progress.update(OperationStage.PREPARING, 0, 1, "Preparing world history");
        this.projectService.bootstrapWorld(server);
        progress.update(OperationStage.FINALIZING, 1, 1, "World history ready");
        LumaMod.LOGGER.info("Completed async world origin metadata bootstrap");
    }

    private boolean chunkLoadingActive(MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            if (level.getChunkSource().getPendingTasksCount() > 0) {
                return true;
            }
        }
        return false;
    }

}
