package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.ActionReplayTarget;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.service.WorldActionHistory;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Publishes the world and its shared cursor under the existing Restore journal. */
public final class ActionReplayPublication implements RestorePublication {
    private final RestorePublication world;
    private final WorldActionHistory history;
    private final UUID operation;
    private final ActionReplayTarget step;
    private final boolean applied;
    private final Consumer<Boolean> install;
    private boolean returnInstalled;

    public ActionReplayPublication(RestorePublication world, WorldActionHistory history,
            UUID operation, ActionReplayTarget step, boolean applied, Consumer<Boolean> install) {
        this.world = Objects.requireNonNull(world, "world");
        this.history = Objects.requireNonNull(history, "history");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.step = Objects.requireNonNull(step, "step");
        this.applied = applied;
        this.install = Objects.requireNonNull(install, "install");
    }

    @Override public void publish(PreparedRestore restore) throws IOException {
        persist(restore);
        finishOnServer(restore);
    }
    @Override public void persist(PreparedRestore restore) throws IOException {
        world.persist(restore);
        history.publishReplay(operation, step, applied);
    }
    @Override public void finishOnServer(PreparedRestore restore) throws IOException { world.finishOnServer(restore); }
    @Override public void installed() throws IOException { world.installed(); install.accept(applied); }
    @Override public boolean isDurable() { return world.isDurable(); }
    @Override public boolean awaitDurable(long deadline) throws IOException { return world.awaitDurable(deadline); }
    @Override public void persistReturn(PreparedRestore restore) throws IOException {
        world.persistReturn(restore);
        history.publishReplay(operation, step, !applied);
    }
    @Override public void finishReturnOnServer(PreparedRestore restore) throws IOException { world.finishReturnOnServer(restore); }
    @Override public boolean isReturnDurable() { return world.isReturnDurable(); }
    @Override public boolean awaitReturnDurable(long deadline) throws IOException {
        if (!world.awaitReturnDurable(deadline)) return false;
        if (!returnInstalled) { install.accept(!applied); returnInstalled = true; }
        return true;
    }
}
