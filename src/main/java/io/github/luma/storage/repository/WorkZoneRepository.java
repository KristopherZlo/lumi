package io.github.luma.storage.repository;

import com.google.gson.JsonSyntaxException;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class WorkZoneRepository {

    public WorkZoneState load(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.workZonesFile())) {
            return WorkZoneState.empty();
        }
        try {
            WorkZoneState state = GsonProvider.gson().fromJson(Files.readString(layout.workZonesFile()), WorkZoneState.class);
            return state == null ? WorkZoneState.empty() : state;
        } catch (JsonSyntaxException exception) {
            StorageIo.quarantineCorruptedFile(layout.workZonesFile(), exception, "malformed work-zone metadata");
            return WorkZoneState.empty();
        }
    }

    public void save(ProjectLayout layout, WorkZoneState state) throws IOException {
        Files.createDirectories(layout.root());
        StorageIo.writeAtomically(layout.workZonesFile(), output -> output.write(
                GsonProvider.gson().toJson(state == null ? WorkZoneState.empty() : state).getBytes(StandardCharsets.UTF_8)
        ));
    }
}
