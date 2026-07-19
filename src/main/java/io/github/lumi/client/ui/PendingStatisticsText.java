package io.github.lumi.client.ui;

import io.github.lumi.domain.model.PendingChangeStatistics;
import java.util.Objects;
import net.minecraft.network.chat.Component;

/** Formats exact pending totals with the existing localized legacy labels. */
final class PendingStatisticsText {
    private PendingStatisticsText() { }

    static Component summary(PendingChangeStatistics statistics) {
        PendingChangeStatistics value = Objects.requireNonNull(
                statistics, "statistics");
        return Component.translatable("luma.dashboard.pending_added")
                .append(" " + value.added() + "   ")
                .append(Component.translatable(
                        "luma.dashboard.pending_removed"))
                .append(" " + value.removed() + "   ")
                .append(Component.translatable(
                        "luma.dashboard.pending_changed"))
                .append(" " + value.changed());
    }
}
