package io.github.luma.network;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.WorkZoneSnapshot;
import io.github.luma.storage.GsonProvider;
import io.github.luma.ui.screen.WorkZoneScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class WorkZoneClientNetworking {

    private static final WorkZoneClientNetworking INSTANCE = new WorkZoneClientNetworking();
    private boolean registered;
    private volatile WorkZoneSnapshot latest = WorkZoneSnapshot.empty("luma.status.zones_loading");

    private WorkZoneClientNetworking() {
    }

    public static WorkZoneClientNetworking getInstance() {
        return INSTANCE;
    }

    public void register() {
        if (this.registered) {
            return;
        }
        WorkZonePayloads.register();
        ClientPlayNetworking.registerGlobalReceiver(WorkZonePayloads.Response.TYPE, this::receive);
        this.registered = true;
    }

    public WorkZoneSnapshot openState(String projectName) {
        if (this.shouldRequestOpenState(projectName)) {
            this.request("open-state", projectName, "", "");
        }
        return this.latest;
    }

    public WorkZoneSnapshot state(String projectName) {
        this.request("state", projectName, "", "");
        return this.latest;
    }

    public void create(String projectName, String zoneName) {
        this.request("create", projectName, "", zoneName);
    }

    public void select(String projectName, String zoneId) {
        this.request("select", projectName, zoneId, "");
    }

    public void save(String projectName, String zoneId, String message) {
        this.request("save", projectName, zoneId, message);
    }

    private void request(String action, String projectName, String zoneId, String zoneName) {
        if (!ClientPlayNetworking.canSend(WorkZonePayloads.Request.TYPE)) {
            this.latest = WorkZoneSnapshot.empty("luma.status.server_mod_required");
            return;
        }
        ClientPlayNetworking.send(new WorkZonePayloads.Request(action, projectName, zoneId, zoneName));
    }

    private boolean shouldRequestOpenState(String projectName) {
        if (this.latest.project() == null) {
            return "luma.status.zones_loading".equals(this.latest.status());
        }
        return projectName != null
                && !projectName.isBlank()
                && !projectName.equals(this.latest.project().name());
    }

    private void receive(WorkZonePayloads.Response response, ClientPlayNetworking.Context context) {
        try {
            WorkZoneSnapshot snapshot = GsonProvider.gson().fromJson(response.json(), WorkZoneSnapshot.class);
            this.latest = snapshot == null ? WorkZoneSnapshot.empty(response.status()) : snapshot;
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to decode work-zone response", exception);
            this.latest = WorkZoneSnapshot.empty("luma.status.operation_failed");
        }
        Minecraft client = context.client();
        client.execute(() -> {
            if (client.screen instanceof WorkZoneScreen screen) {
                screen.refreshFromRemote(this.latest.status());
            }
        });
    }
}
