package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.ActionReplayTarget;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.service.WorldActionHistory;
import io.github.lumi.storage.object.WorldActionIndexCodec;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Publishes the world and its action history under the existing Restore journal. */
public final class ActionReplayPublication implements RestorePublication {
    private final RestorePublication world;
    private final Preparation preparation;
    private final Publication publication;
    private ActionReplayTarget target;
    private Runnable install = () -> { };
    private boolean returnInstalled;

    public ActionReplayPublication(RestorePublication world, WorldActionHistory history,
            UUID operation, ActionReplayTarget step, boolean applied, Consumer<Boolean> install) {
        this(world, ignored -> null, returned -> {
            boolean desired = returned ? !applied : applied;
            history.publishReplay(operation, step, desired);
            return () -> install.accept(desired);
        });
        Objects.requireNonNull(history);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(step);
        Objects.requireNonNull(install);
    }

    private ActionReplayPublication(RestorePublication world, Preparation preparation, Publication publication) {
        this.world = Objects.requireNonNull(world);
        this.preparation = Objects.requireNonNull(preparation);
        this.publication = Objects.requireNonNull(publication);
    }

    public static ActionReplayPublication checkpoint(RestorePublication world, WorldActionHistory history, UUID operation,
            boolean applied, Preparation preparation, Installation install) {
        return new ActionReplayPublication(world, preparation,
                returned -> install.prepare(history.publishCheckpoint(operation, returned ? !applied : applied)));
    }

    @Override public void prepare(PreparedRestore restore) throws IOException {
        world.prepare(restore);
        target = preparation.prepare(restore);
    }
    @Override public Optional<ActionReplayTarget> historyTarget() { return Optional.ofNullable(target); }
    @Override public void publish(PreparedRestore restore) throws IOException { persist(restore); finishOnServer(restore); }
    @Override public void persist(PreparedRestore restore) throws IOException {
        world.persist(restore);
        install = publication.publish(false);
    }
    @Override public void persistReturn(PreparedRestore restore) throws IOException {
        world.persistReturn(restore);
        install = publication.publish(true);
    }
    @Override public void finishOnServer(PreparedRestore restore) throws IOException { world.finishOnServer(restore); }
    @Override public void finishReturnOnServer(PreparedRestore restore) throws IOException { world.finishReturnOnServer(restore); }
    @Override public void installed() throws IOException { world.installed(); install.run(); }
    @Override public boolean isDurable() { return world.isDurable(); }
    @Override public boolean awaitDurable(long deadline) throws IOException { return world.awaitDurable(deadline); }
    @Override public boolean isReturnDurable() { return world.isReturnDurable(); }
    @Override public boolean awaitReturnDurable(long deadline) throws IOException {
        if (!world.awaitReturnDurable(deadline)) return false;
        if (!returnInstalled) { install.run(); returnInstalled = true; }
        return true;
    }

    @FunctionalInterface public interface Preparation { ActionReplayTarget prepare(PreparedRestore restore) throws IOException; }
    @FunctionalInterface public interface Installation { Runnable prepare(WorldActionIndexCodec.State state) throws IOException; }
    @FunctionalInterface private interface Publication { Runnable publish(boolean returned) throws IOException; }
}
