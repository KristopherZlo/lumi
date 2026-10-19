package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.PreparedRestore;
import java.io.IOException;

/** Publishes durable history selection only after a Restore verifies exactly. */
@FunctionalInterface
public interface RestorePublication {
    /** Worker phase before journal creation; optional action roots must already be durable. */
    default void prepare(PreparedRestore restore) throws IOException { }

    default java.util.Optional<io.github.lumi.domain.model.ActionReplayTarget> historyTarget() { return java.util.Optional.empty(); }

    /** Synchronous standalone publication, retained for repository callers. */
    void publish(PreparedRestore restore) throws IOException;

    /** Worker phase; implementations with server-owned tracking must split publication. */
    default void persist(PreparedRestore restore) throws IOException { publish(restore); }

    /** Server phase after file publication, before waiting for index durability. */
    default void finishOnServer(PreparedRestore restore) throws IOException { }

    /** Installs server-owned runtime selection after publication is durable. */
    default void installed() throws IOException { }

    default boolean isDurable() {
        return true;
    }

    default boolean awaitDurable(long deadlineNanos) throws IOException {
        return isDurable();
    }

    /** Reconciles durable pending state when target failure verifies the return state. */
    default void publishReturn(PreparedRestore restore) throws IOException { }

    default void persistReturn(PreparedRestore restore) throws IOException { publishReturn(restore); }

    default void finishReturnOnServer(PreparedRestore restore) throws IOException { }

    default boolean isReturnDurable() {
        return true;
    }

    default boolean awaitReturnDurable(long deadlineNanos) throws IOException {
        return isReturnDurable();
    }
}
