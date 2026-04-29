package io.github.luma.client.onboarding;

import io.github.luma.LumaMod;
import io.github.luma.storage.GsonProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

public final class ClientOnboardingStateRepository {

    private static final String FILE_NAME = "lumi-client.json";

    private final Path file;

    public ClientOnboardingStateRepository() {
        this(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    public ClientOnboardingStateRepository(Path file) {
        this.file = file;
    }

    public ClientOnboardingState load() {
        if (this.file == null || !Files.exists(this.file)) {
            return ClientOnboardingState.empty();
        }

        try {
            ClientOnboardingState state = GsonProvider.gson().fromJson(
                    Files.readString(this.file),
                    ClientOnboardingState.class
            );
            return state == null ? ClientOnboardingState.empty() : state.normalized();
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Ignoring malformed Lumi client onboarding state at {}", this.file, exception);
            return ClientOnboardingState.empty();
        }
    }

    public void save(ClientOnboardingState state) throws IOException {
        if (this.file == null) {
            return;
        }
        Path parent = this.file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = this.file.resolveSibling(this.file.getFileName().toString() + ".tmp");
        Files.writeString(
                temp,
                GsonProvider.gson().toJson(state == null ? ClientOnboardingState.empty() : state.normalized()),
                StandardCharsets.UTF_8
        );
        try {
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
