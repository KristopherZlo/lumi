package io.github.lumi.minecraft.operation;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.WorldStateApply;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns one bounded immutable Restore plan until a matching return point claims it. */
public final class RestorePrewarm implements AutoCloseable {
    private final BranchRef source;
    private final CommitId target;
    private final RestoreService restores;
    private final CompletableFuture<RestoreOperation.PrewarmedRestore> future;
    private final PreparedMutationOwnership<RestoreOperation.PrewarmedRestore> ownership;
    private boolean ready;

    RestorePrewarm(
            BranchRef source,
            CommitId target,
            RestoreService restores,
            CompletableFuture<RestoreOperation.PrewarmedRestore> future) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.restores = Objects.requireNonNull(restores, "restores");
        this.future = Objects.requireNonNull(future, "future");
        ownership = new PreparedMutationOwnership<>(
                future, RestoreOperation.PrewarmedRestore::close);
    }

    public boolean matches(BranchRef expected, CommitId requestedTarget) {
        return source.equals(expected) && target.equals(requestedTarget);
    }

    public Optional<RestoreOperation.PrewarmedRestore> claim(
            SaveResult returnPoint, WorldStateApply world) throws IOException {
        Objects.requireNonNull(returnPoint, "returnPoint");
        var returnSpawns = restores.playerSpawnsWhenTreeMatches(
                source.commit(), returnPoint.commitId());
        if (returnSpawns.isEmpty()) {
            LumiMod.LOGGER.info(
                    "Lumi Restore prewarm miss: source={}, return={}, target={}; "
                            + "using exact fallback",
                    source.commit(), returnPoint.commitId(), target);
            close();
            return Optional.empty();
        }
        try {
            RestoreOperation.PrewarmedRestore warmed = future.join();
            RestoreOperation.PrewarmedRestore adjusted =
                    warmed.withReturnPlayerSpawns(
                            Objects.requireNonNull(world, "world"),
                            returnSpawns.orElseThrow());
            ownership.claim();
            LumiMod.LOGGER.info(
                    "Lumi Restore prewarm hit: source={}, return={}, target={}",
                    source.commit(), returnPoint.commitId(), target);
            return Optional.of(adjusted);
        } catch (IOException failed) {
            close();
            throw failed;
        } catch (CompletionException | IllegalStateException failed) {
            close();
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            LumiMod.LOGGER.warn(
                    "Lumi Restore prewarm failed; using exact fallback", cause);
            return Optional.empty();
        }
    }

    public boolean advanceUntil(long deadlineNanos) throws IOException {
        if (!future.isDone()) {
            return false;
        }
        try {
            ready = future.join().prewarmUntil(deadlineNanos);
            return ready;
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Cannot prewarm Restore chunks", cause);
        }
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public void close() {
        try {
            ownership.close();
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Cannot close Lumi Restore prewarm", failed);
        }
    }
}
