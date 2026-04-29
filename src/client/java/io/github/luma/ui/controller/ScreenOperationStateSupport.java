package io.github.luma.ui.controller;

import io.github.luma.domain.model.OperationSnapshot;
import java.util.Set;
import net.minecraft.network.chat.Component;

public final class ScreenOperationStateSupport {

    private static final Set<String> TRANSIENT_OPERATION_STATUS_KEYS = Set.of(
            "luma.status.save_started",
            "luma.status.amend_started",
            "luma.status.restore_started",
            "luma.status.preview_requested",
            "luma.status.recovery_save_started",
            "luma.status.draft_restore_started",
            "luma.status.merge_started"
    );

    private ScreenOperationStateSupport() {
    }

    public static boolean blocksMutationActions(OperationSnapshot snapshot) {
        return snapshot != null && !snapshot.terminal();
    }

    public static String normalizeStatusKey(String statusKey, OperationSnapshot snapshot, String readyStatusKey) {
        String resolvedStatus = statusKey == null || statusKey.isBlank() ? readyStatusKey : statusKey;
        if (snapshot == null && TRANSIENT_OPERATION_STATUS_KEYS.contains(resolvedStatus)) {
            return readyStatusKey;
        }
        return resolvedStatus;
    }

    public static boolean shouldShowStatusBanner(String statusKey, OperationSnapshot snapshot, String readyStatusKey) {
        String resolvedStatus = normalizeStatusKey(statusKey, snapshot, readyStatusKey);
        if (snapshot != null && snapshot.terminal()) {
            return snapshot.failed();
        }
        return !resolvedStatus.equals(readyStatusKey);
    }

    public static Component bannerText(String statusKey, OperationSnapshot snapshot, String readyStatusKey) {
        String resolvedStatus = normalizeStatusKey(statusKey, snapshot, readyStatusKey);
        if (snapshot == null || !snapshot.terminal()) {
            return Component.translatable(resolvedStatus);
        }
        if (snapshot.failed()) {
            return snapshot.detail() == null || snapshot.detail().isBlank()
                    ? Component.translatable("luma.status.operation_failed")
                    : Component.literal(snapshot.detail());
        }
        return Component.translatable(readyStatusKey);
    }
}
