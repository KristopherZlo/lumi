package io.github.luma.storage.repository;

import io.github.luma.domain.model.HistoryProtectionState;
import io.github.luma.domain.model.HistoryProtectionStatus;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/** Atomic persistence for a project's durable degraded-history marker. */
public final class HistoryProtectionRepository {

    public void saveDegraded(ProjectLayout layout, HistoryProtectionStatus status) throws IOException {
        if (layout == null || status == null || status.state() != HistoryProtectionState.DEGRADED) {
            throw new IllegalArgumentException("A degraded history status is required");
        }
        StorageIo.writeAtomically(layout.historyProtectionFile(), output -> output.write(
                GsonProvider.gson().toJson(status).getBytes(StandardCharsets.UTF_8)
        ));
    }

    public Optional<HistoryProtectionStatus> loadDegraded(ProjectLayout layout) throws IOException {
        if (layout == null || !Files.exists(layout.historyProtectionFile())) {
            return Optional.empty();
        }
        HistoryProtectionStatus status = GsonProvider.gson().fromJson(
                Files.readString(layout.historyProtectionFile()),
                HistoryProtectionStatus.class
        );
        if (status == null || status.state() != HistoryProtectionState.DEGRADED) {
            throw new IOException("Invalid history protection marker");
        }
        return Optional.of(status);
    }
}
