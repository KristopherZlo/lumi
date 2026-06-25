package io.github.luma.network;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneSnapshot;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class WorkZoneServerNetworking {

    private final ProjectService projectService = new ProjectService();
    private final WorkZoneService workZoneService = new WorkZoneService();
    private final VersionService versionService = new VersionService();

    public void register() {
        WorkZonePayloads.register();
        ServerPlayNetworking.registerGlobalReceiver(WorkZonePayloads.Request.TYPE, this::receive);
    }

    private void receive(WorkZonePayloads.Request request, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        MinecraftServer server = context.server();
        if (!LumaAccessControl.getInstance().canUse(player)) {
            this.send(player, WorkZoneSnapshot.empty("luma.status.admin_required"));
            return;
        }

        try {
            String action = request.action();
            BuildProject project = this.project(request, server, player, "open-state".equals(action)
                    || "create".equals(action)
                    || "select".equals(action)
                    || "save".equals(action)
                    || "amend".equals(action));
            if (project == null) {
                this.send(player, WorkZoneSnapshot.empty("luma.status.no_workspace"));
                return;
            }

            var layout = this.projectService.resolveLayout(server, project.name());
            String actor = player.getName().getString();
            if ("create".equals(action)) {
                this.workZoneService.createZone(layout, project.id().toString(), request.zoneName(), actor, Instant.now());
            } else if ("select".equals(action)) {
                this.workZoneService.selectZone(layout, actor, request.zoneId());
            } else if ("save".equals(action) || "amend".equals(action)) {
                boolean amend = "amend".equals(action);
                this.save(request, player, layout, project, actor, amend);
                this.send(player, this.snapshot(server, player, project, actor, amend
                        ? "luma.status.amend_started"
                        : "luma.status.save_started"));
                return;
            }

            this.send(player, this.snapshot(server, player, project, actor, "luma.status.zones_ready"));
        } catch (IllegalArgumentException exception) {
            this.send(player, WorkZoneSnapshot.empty(this.illegalArgumentStatus(exception)));
        } catch (IllegalStateException exception) {
            this.send(player, WorkZoneSnapshot.empty(this.illegalStateStatus(exception)));
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to handle work-zone network request {}", request.action(), exception);
            this.send(player, WorkZoneSnapshot.empty("luma.status.operation_failed"));
        }
    }

    private void save(
            WorkZonePayloads.Request request,
            ServerPlayer player,
            ProjectLayout layout,
            BuildProject project,
            String actor,
            boolean amend
    ) throws Exception {
        String message = request.zoneName() == null ? "" : request.zoneName().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("Save message is required");
        }
        this.workZoneService.selectZone(layout, actor, request.zoneId());
        if (amend) {
            this.versionService.startAmendVersion(player.level(), project.name(), message, actor, ProjectVersionTags.parse(request.tags()));
            return;
        }
        this.versionService.startSaveVersion(player.level(), project.name(), message, actor, ProjectVersionTags.parse(request.tags()));
    }

    private BuildProject project(
            WorkZonePayloads.Request request,
            MinecraftServer server,
            ServerPlayer player,
            boolean createIfMissing
    ) throws Exception {
        if (!request.projectName().isBlank()) {
            return this.projectService.loadProject(server, request.projectName());
        }
        if (createIfMissing) {
            return this.projectService.ensureWorldProject(player.level(), player.getName().getString());
        }
        return this.projectService.findWorldProject(player.level()).orElse(null);
    }

    private WorkZoneSnapshot snapshot(
            MinecraftServer server,
            ServerPlayer player,
            BuildProject project,
            String actor,
            String status
    ) throws Exception {
        var layout = this.projectService.resolveLayout(server, project.name());
        WorkZoneState zones = this.workZoneService.load(layout);
        return new WorkZoneSnapshot(
                project,
                this.projectService.loadVariants(server, project.name()),
                this.projectService.loadWorkZoneVersions(server, project.name()),
                zones,
                actor,
                this.focusedZoneId(zones, actor, WorkZoneCell.from(BlockPoint.from(player.blockPosition()))),
                status
        );
    }

    private String focusedZoneId(WorkZoneState zones, String actor, WorkZoneCell playerCell) {
        String activeZoneId = zones.activeZoneId(actor);
        if (!activeZoneId.isBlank()) {
            return activeZoneId;
        }
        Optional<WorkZone> entered = zones.zones().stream()
                .filter(zone -> zone.contains(playerCell))
                .findFirst();
        return entered.map(WorkZone::id).orElse("");
    }

    private void send(ServerPlayer player, WorkZoneSnapshot snapshot) {
        ServerPlayNetworking.send(player, new WorkZonePayloads.Response(
                snapshot.status(),
                GsonProvider.compactGson().toJson(snapshot)
        ));
    }

    private String illegalArgumentStatus(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("zone name")) {
            return "luma.status.zone_name_required";
        }
        if (message.contains("save message")) {
            return "luma.status.quick_save_name_required";
        }
        if (message.contains("unknown zone")) {
            return "luma.status.zone_not_found";
        }
        if (message.contains("no pending tracked changes")) {
            return "luma.status.no_changes_to_save";
        }
        return "luma.status.operation_failed";
    }

    private String illegalStateStatus(IllegalStateException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("admin") || message.contains("cheats")) {
            return "luma.status.admin_required";
        }
        return "luma.status.world_operation_busy";
    }
}
