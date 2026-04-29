package io.github.luma.storage.repository;

import io.github.luma.domain.model.HistoryTombstones;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

public final class HistoryTombstoneRepository {

    public HistoryTombstones load(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.historyTombstonesFile())) {
            return HistoryTombstones.empty();
        }
        HistoryTombstones tombstones = GsonProvider.gson().fromJson(
                Files.readString(layout.historyTombstonesFile()),
                HistoryTombstones.class
        );
        return tombstones == null ? HistoryTombstones.empty() : tombstones;
    }

    public void save(ProjectLayout layout, HistoryTombstones tombstones) throws IOException {
        Files.createDirectories(layout.root());
        StorageIo.writeAtomically(layout.historyTombstonesFile(), output -> output.write(
                GsonProvider.gson().toJson(tombstones == null ? HistoryTombstones.empty() : tombstones)
                        .getBytes(StandardCharsets.UTF_8)
        ));
    }

    public HistoryTombstones tombstoneVersion(ProjectLayout layout, String versionId, Instant now) throws IOException {
        HistoryTombstones next = this.load(layout).withDeletedVersion(versionId, now);
        this.save(layout, next);
        return next;
    }

    public HistoryTombstones tombstoneVariant(ProjectLayout layout, String variantId, Instant now) throws IOException {
        HistoryTombstones next = this.load(layout).withDeletedVariant(variantId, now);
        this.save(layout, next);
        return next;
    }
}
