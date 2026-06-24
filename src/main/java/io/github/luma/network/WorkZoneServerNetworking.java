package io.github.luma.network;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneSnapshot;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.minecraft.access.LumaAccessControl;
import io.github.luma.storage.GsonProvider;
import java.time.Instant;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class WorkZoneServerNetworking {

    private final ProjectService projectService = new ProjectService();
    private final WorkZoneService workZoneService = new WorkZoneService();

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
                    || "select".equals(action));
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
            }

            this.send(player, this.snapshot(server, player, project, actor, "luma.status.zones_ready"));
        } catch (IllegalArgumentException exception) {
            this.send(player, WorkZoneSnapshot.empty("luma.status.zone_name_required"));
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to handle work-zone network request {}", request.action(), exception);
            this.send(player, WorkZoneSnapshot.empty("luma.status.operation_failed"));
        }
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
}
