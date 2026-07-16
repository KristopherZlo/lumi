package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.storage.object.CommitCodec;
import io.github.lumi.storage.object.ObjectStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class CommitRepository {
    private final ObjectStore store;
    private final CommitCodec codec = new CommitCodec();

    public CommitRepository(Path dimensionRepository) {
        Objects.requireNonNull(dimensionRepository, "dimensionRepository");
        this.store = new ObjectStore(dimensionRepository.resolve("commits"));
    }

    public CommitId write(Commit commit) throws IOException {
        return new CommitId(store.write(codec.encode(commit)));
    }

    public Commit read(CommitId id) throws IOException {
        Objects.requireNonNull(id, "id");
        return codec.decode(store.read(id.value()));
    }

    public byte[] readCanonical(CommitId id) throws IOException {
        return store.read(Objects.requireNonNull(id, "id").value());
    }

    public CommitId writeCanonical(CommitId expected, byte[] payload)
            throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(payload, "payload");
        codec.decode(payload);
        CommitId actual = new CommitId(io.github.lumi.domain.model.ObjectId.hash(payload));
        if (!actual.equals(expected)) {
            throw new IOException("Commit payload hash does not match package manifest");
        }
        return new CommitId(store.write(payload));
    }
}
