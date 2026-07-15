package io.github.lumi.storage.repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;

/** Maps a Minecraft dimension identifier to one traversal-safe repository directory. */
public final class DimensionRepositoryLayout {
    private static final int MAX_ID_LENGTH = 1024;
    private final Path historyRoot;

    public DimensionRepositoryLayout(Path worldRoot) {
        historyRoot = Objects.requireNonNull(worldRoot, "worldRoot")
                .toAbsolutePath().normalize().resolve("lumi").resolve("history-v2");
    }

    public Path resolve(String dimensionId) {
        validate(dimensionId);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(dimensionId.getBytes(StandardCharsets.UTF_8));
        return historyRoot.resolve(encoded);
    }

    public String dimensionId(Path repository) {
        Path normalized = Objects.requireNonNull(repository, "repository").toAbsolutePath().normalize();
        if (!historyRoot.equals(normalized.getParent())) {
            throw new IllegalArgumentException("Path is not a Lumi dimension repository");
        }
        try {
            String encoded = normalized.getFileName().toString();
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            validate(decoded);
            if (!resolve(decoded).equals(normalized)) {
                throw new IllegalArgumentException("Dimension repository name is not canonical");
            }
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Lumi dimension repository path", invalid);
        }
    }

    private static void validate(String dimensionId) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (dimensionId.isBlank() || !dimensionId.equals(dimensionId.trim())
                || dimensionId.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid dimension ID");
        }
    }
}
