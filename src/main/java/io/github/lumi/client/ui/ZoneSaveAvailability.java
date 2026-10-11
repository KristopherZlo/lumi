package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.UUID;

/** Builder-facing Save and Amend eligibility for one current zone scope. */
public record ZoneSaveAvailability(String saveReason, String amendReason) {
    public String reason(SaveScreenController.Intent intent) {
        return intent == SaveScreenController.Intent.SAVE ? saveReason : amendReason;
    }

    public static ZoneSaveAvailability inspect(HistorySnapshotPayload snapshot, UUID zoneId,
            ClientPendingStatisticsStore statistics) {
        if (snapshot == null) return blocked("luma.status.dimension_not_ready");
        var zone = snapshot.zones().stream().filter(value -> value.id().equals(zoneId)).findFirst();
        if (zone.isEmpty() || !zone.orElseThrow().active()) return blocked("luma.zones.details_inactive");
        var changed = statistics.zoneHasChanges(snapshot, zoneId);
        if (changed.isEmpty()) {
            String reason = statistics.pending(snapshot) ? "luma.dashboard.pending_calculating"
                    : statistics.result(snapshot).filter(value -> !value.error().isEmpty()
                            && value.pendingRevision() == snapshot.pendingRevision())
                            .map(value -> value.error()).orElse("luma.dashboard.pending_calculating");
            return blocked(reason);
        }
        if (!changed.orElseThrow()) return blocked("luma.zones.pending_clean");
        return new ZoneSaveAvailability("", zone.orElseThrow().versions().isEmpty()
                ? "luma.zones.history_empty" : "");
    }

    private static ZoneSaveAvailability blocked(String reason) {
        return new ZoneSaveAvailability(reason, reason);
    }
}
