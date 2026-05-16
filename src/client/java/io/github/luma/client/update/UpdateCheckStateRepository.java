package io.github.luma.client.update;

import io.github.luma.LumaMod;
import io.github.luma.storage.GsonProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

public final class UpdateCheckStateRepository implements UpdateStateRepository {

    private static final String FILE_NAME = "lumi-update-check.json";

    private final Path file;

    public UpdateCheckStateRepository() {
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    public UpdateCheckStateRepository(Path file) {
        this.file = file;
    }

    @Override
    public UpdateCheckState load() {
        if (this.file == null || !Files.exists(this.file)) {
            return UpdateCheckState.empty();
        }
        try {
            UpdateCheckState state = GsonProvider.gson().fromJson(
                    Files.readString(this.file),
                    UpdateCheckState.class
            );
            return state == null ? UpdateCheckState.empty() : state;
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Ignoring malformed Lumi update-check state at {}", this.file, exception);
            return UpdateCheckState.empty();
        }
    }

    @Override
    public void save(UpdateCheckState state) {
        if (this.file == null) {
            return;
        }
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = this.file.resolveSibling(this.file.getFileName().toString() + ".tmp");
            Files.writeString(
                    temp,
                    GsonProvider.gson().toJson(state == null ? UpdateCheckState.empty() : state),
                    StandardCharsets.UTF_8
            );
            try {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Failed to save Lumi update-check state at {}", this.file, exception);
        }
    }
}
