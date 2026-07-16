package io.github.lumi.minecraft.operation;

import java.util.Objects;
import java.util.UUID;

public record OperationTicket(UUID id) {
    public OperationTicket {
        Objects.requireNonNull(id, "id");
    }
}
