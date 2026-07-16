package io.github.lumi.storage.object;

import io.github.lumi.domain.model.ObjectId;
import java.nio.file.Path;
import java.util.Objects;

record PackedObject(
        ObjectId id, Path pack, long offset, int rawLength, int compressedLength) {
    PackedObject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pack, "pack");
    }
}
