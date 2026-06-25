package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.network.WorkZoneClientNetworking;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.ui.state.WorkZoneViewState;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

public final class WorkZoneScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final WorkZoneService workZoneService = new WorkZoneService();
    private final RecoveryService recoveryService = new RecoveryService();

    public WorkZoneViewState load(String projectName, String status) {
        if (!this.client.hasSingleplayerServer()) {
            return WorkZoneViewState.fromSnapshot(WorkZoneClientNetworking.getInstance().openState(projectName));
        }
        try {
            MinecraftServer server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var layout = this.projectService.resolveLayout(server, projectName);
            var project = this.projectService.loadProject(server, projectName);
            WorkZoneState zones = this.workZoneService.load(layout);
            String actor = this.actor();
            return new WorkZoneViewState(
                    project,
                    this.projectService.loadVariants(server, projectName),
                    this.projectService.loadWorkZoneVersions(server, projectName),
                    zones,
                    actor,
                    this.focusedZoneId(zones, actor),
                    this.pendingChanges(server, project.name(), layout, actor),
                    status
            );
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to load work zones for project {}", projectName, exception);
            return new WorkZoneViewState(null, List.<ProjectVariant>of(), List.of(), WorkZoneState.empty(), this.actor(), "", "luma.status.project_failed");
        }
    }

    public String createZone(String projectName, String name) {
        if (!this.client.hasSingleplayerServer()) {
            WorkZoneClientNetworking.getInstance().create(projectName, name);
            return "luma.status.zones_loading";
        }
        try {
            MinecraftServer server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var project = this.projectService.loadProject(server, projectName);
            this.workZoneService.createZone(
                    this.projectService.resolveLayout(server, projectName),
                    project.id().toString(),
                    name,
                    this.actor(),
                    Instant.now()
            );
            return "luma.status.zone_created";
        } catch (IllegalArgumentException exception) {
            return "luma.status.zone_name_required";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to create work zone for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    public String selectZone(String projectName, String zoneId) {
        if (!this.client.hasSingleplayerServer()) {
            WorkZoneClientNetworking.getInstance().select(projectName, zoneId);
            return "luma.status.zones_loading";
        }
        try {
            MinecraftServer server = ClientProjectAccess.requireSingleplayerServer(this.client);
            this.workZoneService.selectZone(this.projectService.resolveLayout(server, projectName), this.actor(), zoneId);
            return zoneId == null || zoneId.isBlank() ? "luma.status.zone_cleared" : "luma.status.zone_selected";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to select work zone for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    public String saveZone(String projectName, String zoneId, String message) {
        return this.saveZone(projectName, zoneId, message, List.of());
    }

    public String saveZone(String projectName, String zoneId, String message, List<String> tags) {
        return this.startZoneVersion(projectName, zoneId, message, tags, false);
    }

    public String amendZone(String projectName, String zoneId, String message, List<String> tags) {
        return this.startZoneVersion(projectName, zoneId, message, tags, true);
    }

    private String startZoneVersion(String projectName, String zoneId, String message, List<String> tags, boolean amend) {
        String normalizedMessage = message == null ? "" : message.trim();
        if (normalizedMessage.isBlank()) {
            return "luma.status.quick_save_name_required";
        }
        if (zoneId == null || zoneId.isBlank()) {
            return "luma.status.zone_not_found";
        }
        if (!this.client.hasSingleplayerServer()) {
            if (amend) {
                WorkZoneClientNetworking.getInstance().amend(projectName, zoneId, normalizedMessage, tags);
            } else {
                WorkZoneClientNetworking.getInstance().save(projectName, zoneId, normalizedMessage, tags);
            }
            return "luma.status.zones_loading";
        }
        try {
            MinecraftServer server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var layout = this.projectService.resolveLayout(server, projectName);
            this.workZoneService.selectZone(layout, this.actor(), zoneId);
            if (amend) {
                this.versionService.startAmendVersion(
                        ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                        projectName,
                        normalizedMessage,
                        this.actor(),
                        tags == null ? List.of() : tags
                );
                return "luma.status.amend_started";
            }
            this.versionService.startSaveVersion(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    normalizedMessage,
                    this.actor(),
                    tags == null ? List.of() : tags
            );
            return "luma.status.save_started";
        } catch (IllegalArgumentException exception) {
            return this.illegalArgumentStatus(exception);
        } catch (IllegalStateException exception) {
            return this.illegalStateStatus(exception);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to save work zone {} for project {}", zoneId, projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    private String actor() {
        return this.client.getUser() == null ? "player" : this.client.getUser().getName();
    }

    private String focusedZoneId(WorkZoneState zones, String actor) {
        String activeZoneId = zones.activeZoneId(actor);
        if (!activeZoneId.isBlank() || this.client.player == null) {
            return activeZoneId;
        }
        WorkZoneCell playerCell = WorkZoneCell.from(BlockPoint.from(this.client.player.blockPosition()));
        List<String> matches = zones.zones().stream()
                .filter(zone -> zone.contains(playerCell))
                .map(zone -> zone.id())
                .toList();
        return matches.size() == 1 ? matches.getFirst() : "";
    }

    private PendingChangeSummary pendingChanges(
            MinecraftServer server,
            String projectName,
            ProjectLayout layout,
            String actor
    ) throws Exception {
        WorkZone activeZone = this.workZoneService.activeZone(layout, actor).orElse(null);
        if (activeZone == null) {
            return PendingChangeSummary.empty();
        }
        RecoveryDraft draft = this.recoveryService.loadDraft(server, projectName).orElse(null);
        return VersionService.summarizePendingForZone(draft, activeZone);
    }

    private String illegalArgumentStatus(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
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
