package io.github.luma.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.service.PreviewCaptureRequestService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PreviewCaptureRequestRepository;
import io.github.luma.storage.repository.VersionRepository;
import io.wispforest.worldmesher.WorldMesh;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import net.minecraft.client.Minecraft;

public final class PreviewCaptureCoordinator {

    private static final PreviewCaptureCoordinator INSTANCE = new PreviewCaptureCoordinator();
    private static final ExecutorService BUILD_EXECUTOR = Executors.newSingleThreadExecutor(new PreviewThreadFactory());

    private final ProjectService projectService = new ProjectService();
    private final PreviewCaptureRequestRepository requestRepository = new PreviewCaptureRequestRepository();
    private final PreviewCaptureRequestService requestService = new PreviewCaptureRequestService();
    private final VersionRepository versionRepository = new VersionRepository();
    private final TexturedPreviewCaptureService captureService = new TexturedPreviewCaptureService();

    private ActiveCapture activeCapture;
    private int scanCooldownTicks;

    private PreviewCaptureCoordinator() {
    }

    public static PreviewCaptureCoordinator getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft client) {
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null || client.level == null) {
            this.discardActiveCapture();
            return;
        }

        if (this.activeCapture != null) {
            this.tickActiveCapture(client);
            return;
        }

        if (this.scanCooldownTicks > 0) {
            this.scanCooldownTicks -= 1;
            return;
        }

        this.startNextCapture(client);
    }

    private void tickActiveCapture(Minecraft client) {
        if (!this.activeCapture.buildFuture().isDone()) {
            return;
        }

        if (this.activeCapture.pendingCapture() == null) {
            if (RenderSystem.isOnRenderThread()) {
                this.beginReadback(client);
            } else {
                client.execute(() -> this.beginReadback(client));
            }
            return;
        }

        if (this.activeCapture.pendingCapture().imageFuture().isDone()) {
            this.finishCapture(client);
        }
    }

    private void beginReadback(Minecraft client) {
        ActiveCapture capture = this.activeCapture;
        if (capture == null) {
            return;
        }

        try {
            capture.buildFuture().join();
            if (client.level == null || !client.level.dimension().identifier().toString().equals(capture.project().dimensionId())) {
                this.completeCapture(capture, false);
                return;
            }
            if (!capture.mesh().canRender()) {
                throw new IllegalStateException("Preview mesh finished building but is not renderable");
            }

            this.activeCapture = capture.withPendingCapture(this.captureService.capture(capture.request().bounds(), capture.mesh()));
        } catch (Exception exception) {
            LumaMod.LOGGER.warn(
                    "Failed to render textured preview for version {} in project {}",
                    capture.request().versionId(),
                    capture.project().name(),
                    exception
            );
            this.completeCapture(capture, false);
        }
    }

    private void finishCapture(Minecraft client) {
        ActiveCapture capture = this.activeCapture;
        if (capture == null || capture.pendingCapture() == null) {
            return;
        }

        try (NativeImage image = capture.pendingCapture().imageFuture().join()) {
            Files.createDirectories(capture.layout().previewsDir());
            image.writeToFile(capture.layout().previewFile(capture.request().versionId()));

            ProjectVersion version = this.versionRepository.load(capture.layout(), capture.request().versionId())
                    .orElseThrow(() -> new IllegalArgumentException("Version not found: " + capture.request().versionId()));
            this.versionRepository.save(capture.layout(), new ProjectVersion(
                    version.id(),
                    version.projectId(),
                    version.variantId(),
                    version.parentVersionId(),
                    version.snapshotId(),
                    version.patchIds(),
                    version.versionKind(),
                    version.author(),
                    version.message(),
                    version.stats(),
                    new PreviewInfo(
                            capture.layout().previewFile(version.id()).getFileName().toString(),
                            capture.pendingCapture().width(),
                            capture.pendingCapture().height()
                    ),
                    version.sourceInfo(),
                    version.createdAt()
            ));
            this.requestService.clear(capture.layout(), capture.request().versionId());
            LumaMod.LOGGER.info("Rendered textured preview for version {} in project {}", version.id(), capture.project().name());
            LumaDebugLog.log(capture.project(), "preview", "Rendered textured preview for version {}", version.id());
            this.completeCapture(capture, true);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn(
                    "Failed to store textured preview for version {} in project {}",
                    capture.request().versionId(),
                    capture.project().name(),
                    exception
            );
            this.completeCapture(capture, false);
        }
    }

    private void startNextCapture(Minecraft client) {
        String dimensionId = client.level.dimension().identifier().toString();
        try {
            for (BuildProject project : this.projectService.listProjects(client.getSingleplayerServer())) {
                if (!project.settings().previewGenerationEnabled() || !dimensionId.equals(project.dimensionId())) {
                    continue;
                }

                ProjectLayout layout = this.projectService.resolveLayout(client.getSingleplayerServer(), project.name());
                List<io.github.luma.domain.model.PreviewCaptureRequest> requests = this.requestRepository.loadAll(layout);
                for (io.github.luma.domain.model.PreviewCaptureRequest request : requests) {
                    if (!dimensionId.equals(request.dimensionId()) || request.bounds() == null) {
                        continue;
                    }

                    WorldMesh mesh = new WorldMesh.Builder(
                            client.level,
                            request.bounds().min().toBlockPos(),
                            request.bounds().max().toBlockPos()
                    ).useGlobalNeighbors().build();
                    CompletableFuture<Void> buildFuture = mesh.scheduleRebuild(BUILD_EXECUTOR);
                    this.activeCapture = new ActiveCapture(project, layout, request, mesh, buildFuture, null);
                    LumaMod.LOGGER.info("Queued client preview render for version {} in project {}", request.versionId(), project.name());
                    LumaDebugLog.log(project, "preview", "Building textured preview mesh for version {} with bounds {}", request.versionId(), request.bounds());
                    return;
                }
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to scan pending preview requests", exception);
            this.scanCooldownTicks = 40;
        }
    }

    private void discardActiveCapture() {
        if (this.activeCapture == null) {
            return;
        }
        this.completeCapture(this.activeCapture, false);
    }

    private void completeCapture(ActiveCapture capture, boolean success) {
        capture.mesh().reset();
        if (capture.pendingCapture() != null) {
            capture.pendingCapture().renderTarget().destroyBuffers();
        }
        this.activeCapture = null;
        this.scanCooldownTicks = success ? 20 : 40;
    }

    private record ActiveCapture(
            BuildProject project,
            ProjectLayout layout,
            io.github.luma.domain.model.PreviewCaptureRequest request,
            WorldMesh mesh,
            CompletableFuture<Void> buildFuture,
            TexturedPreviewCaptureService.PendingPreviewCapture pendingCapture
    ) {
        private ActiveCapture withPendingCapture(TexturedPreviewCaptureService.PendingPreviewCapture pendingCapture) {
            return new ActiveCapture(this.project, this.layout, this.request, this.mesh, this.buildFuture, pendingCapture);
        }
    }

    private static final class PreviewThreadFactory implements ThreadFactory {

        private int nextIndex = 1;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Lumi-ClientPreview-" + this.nextIndex++);
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        }
    }
}
