package io.github.luma.storage.repository;

import com.google.gson.JsonSyntaxException;
import io.github.luma.domain.model.PlayerRespawnPoint;
import io.github.luma.domain.model.PlayerRespawnState;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class PlayerRespawnRepository {

    public List<PlayerRespawnPoint> loadVersion(ProjectLayout layout, String versionId) throws IOException {
        return this.load(layout).pointsFor(versionId);
    }

    public void saveVersion(ProjectLayout layout, String versionId, List<PlayerRespawnPoint> points) throws IOException {
        this.save(layout, this.load(layout).withVersion(versionId, points));
    }

    private PlayerRespawnState load(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.playerRespawnsFile())) {
            return PlayerRespawnState.empty();
        }
        try {
            PlayerRespawnState state = GsonProvider.gson().fromJson(
                    Files.readString(layout.playerRespawnsFile(), StandardCharsets.UTF_8),
                    PlayerRespawnState.class
            );
            return state == null ? PlayerRespawnState.empty() : state;
        } catch (JsonSyntaxException exception) {
            StorageIo.quarantineCorruptedFile(layout.playerRespawnsFile(), exception, "malformed player respawn metadata");
            return PlayerRespawnState.empty();
        }
    }

    private void save(ProjectLayout layout, PlayerRespawnState state) throws IOException {
        Files.createDirectories(layout.root());
        StorageIo.writeAtomically(layout.playerRespawnsFile(), output -> output.write(
                GsonProvider.gson().toJson(state == null ? PlayerRespawnState.empty() : state)
                        .getBytes(StandardCharsets.UTF_8)
        ));
    }
}
