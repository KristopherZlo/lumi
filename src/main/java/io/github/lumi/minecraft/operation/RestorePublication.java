package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.PreparedRestore;
import java.io.IOException;

/** Publishes durable history selection only after a Restore verifies exactly. */
@FunctionalInterface
public interface RestorePublication {
    void publish(PreparedRestore restore) throws IOException;
}
