package io.github.lumi.client.ui;

import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.network.HistorySnapshotPayload;
import net.minecraft.network.chat.Component;

/** Shared builder-facing labels and visibility rules for version cards. */
final class VersionText {
    private VersionText() { }

    static String name(HistorySnapshotPayload.Version version) {
        return version.kind() == CommitKind.HIDDEN_SAFETY
                ? Component.translatable("luma.history.initial_save").getString()
                : version.message();
    }

    static boolean featured(HistorySnapshotPayload.Version version) {
        return switch (version.kind()) {
            case AUTO, HIDDEN_SAFETY, HIDDEN_RETURN -> false;
            default -> true;
        };
    }

    static boolean immutable(HistorySnapshotPayload.Version version) {
        return version.kind() == CommitKind.HIDDEN_SAFETY
                || version.kind() == CommitKind.HIDDEN_RETURN;
    }
}
