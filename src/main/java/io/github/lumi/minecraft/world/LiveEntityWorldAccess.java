package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Prepared server-world access for one durable live entity state. */
public interface LiveEntityWorldAccess {
    LiveEntityWorldAccess UNSUPPORTED = new LiveEntityWorldAccess() {
        @Override
        public void requirePrepared(Optional<EntityState> state) throws IOException {
            throw new IOException("Live entity access is unavailable");
        }

        @Override
        public Optional<EntityState> read(UUID entityId) throws IOException {
            throw new IOException("Live entity access is unavailable");
        }

        @Override
        public void write(UUID entityId, Optional<EntityState> replacement) throws IOException {
            throw new IOException("Live entity access is unavailable");
        }
    };

    void requirePrepared(Optional<EntityState> state) throws IOException;

    Optional<EntityState> read(UUID entityId) throws IOException;

    void write(UUID entityId, Optional<EntityState> replacement) throws IOException;
}
