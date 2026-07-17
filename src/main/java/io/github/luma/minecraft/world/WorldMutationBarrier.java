package io.github.luma.minecraft.world;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Keeps save and history application isolated from concurrent world edits. */
final class WorldMutationBarrier {

    private final Map<WorldOperationManager.ActiveOperation, Lease> leases = new ConcurrentHashMap<>();

    boolean blocks(ServerLevel level, WorldOperationManager.ActiveOperation activeOperation) {
        if (level == null || activeOperation == null || this.leases.isEmpty()) {
            return false;
        }
        Lease lease = this.leases.get(activeOperation);
        return !WorldMutationContext.captureSuppressed()
                && !WorldMutationContext.internalWorldApplyActive()
                && lease != null
                && lease.belongsTo(level.getServer());
    }

    boolean active() {
        return !this.leases.isEmpty();
    }

    void acquire(WorldOperationManager.ActiveOperation operation, boolean preparedApply) {
        Lease lease = this.createLease(operation.level(), operation.handle().label(), preparedApply);
        if (lease != null) {
            this.leases.put(operation, lease);
            LumaMod.LOGGER.info(
                    "World mutations locked for operation {} ({}) in project {}; serverWasFrozen={}",
                    operation.handle().label(),
                    operation.handle().id(),
                    operation.handle().projectId(),
                    !lease.unfreezeOnRelease
            );
        }
    }

    void release(WorldOperationManager.ActiveOperation operation) {
        Lease lease = this.leases.remove(operation);
        if (lease != null) {
            lease.release("operation-terminal");
        }
    }

    void reconcile(MinecraftServer server, WorldOperationManager.ActiveOperation activeOperation) {
        for (Map.Entry<WorldOperationManager.ActiveOperation, Lease> entry : this.leases.entrySet()) {
            if (entry.getKey() != activeOperation
                    && entry.getValue().belongsTo(server)
                    && this.leases.remove(entry.getKey(), entry.getValue())) {
                LumaMod.LOGGER.warn(
                        "Recovered stale world mutation lock from operation {} ({})",
                        entry.getKey().handle().label(),
                        entry.getKey().handle().id()
                );
                entry.getValue().release("stale-operation");
            }
        }
    }

    void releaseAll(MinecraftServer server) {
        this.reconcile(server, null);
    }

    void logRejectedPlayerMutation(
            WorldOperationManager.ActiveOperation operation,
            String action,
            String actor
    ) {
        Lease lease = this.leases.get(operation);
        if (lease == null || !lease.rejectionLogged.compareAndSet(false, true)) {
            return;
        }
        LumaMod.LOGGER.warn(
                "Rejected player world mutation action={} actor={} while operation {} ({}) is stage={}; "
                        + "the mutation lock will release when the operation becomes terminal",
                action == null || action.isBlank() ? "unknown" : action,
                actor == null || actor.isBlank() ? "unknown" : actor,
                operation.handle().label(),
                operation.handle().id(),
                operation.snapshot().stage()
        );
    }

    private Lease createLease(ServerLevel level, String label, boolean preparedApply) {
        WorldOperationKind kind = WorldOperationKind.fromLabel(label);
        boolean blocks = preparedApply
                ? kind.blocksPreparedMutations()
                : kind.blocksBackgroundMutations();
        if (!blocks) {
            return null;
        }

        MinecraftServer server = level.getServer();
        boolean restoreTickFreeze = !server.tickRateManager().isFrozen();
        if (restoreTickFreeze) {
            server.tickRateManager().setFrozen(true);
        }
        LumaLoadLog.event(
                "world-op",
                "mutation-barrier-acquire",
                "label=" + label + ", alreadyFrozen=" + !restoreTickFreeze
        );
        return new Lease(level, label, restoreTickFreeze);
    }

    private final class Lease {

        private final MinecraftServer server;
        private final String label;
        private final boolean unfreezeOnRelease;
        private final AtomicBoolean rejectionLogged = new AtomicBoolean();
        private boolean released;

        private Lease(ServerLevel level, String label, boolean unfreezeOnRelease) {
            this.server = level.getServer();
            this.label = label;
            this.unfreezeOnRelease = unfreezeOnRelease;
        }

        boolean belongsTo(MinecraftServer server) {
            return this.server == server;
        }

        synchronized void release(String reason) {
            if (this.released) {
                return;
            }
            this.released = true;
            if (this.unfreezeOnRelease) {
                this.server.tickRateManager().setFrozen(false);
            }
            LumaMod.LOGGER.info(
                    "World mutations unlocked for operation {}; reason={}, serverUnfrozen={}",
                    this.label,
                    reason,
                    this.unfreezeOnRelease
            );
            LumaLoadLog.event(
                    "world-op",
                    "mutation-barrier-release",
                    "label=" + this.label
                            + ", reason=" + reason
                            + ", serverUnfrozen=" + this.unfreezeOnRelease
            );
        }
    }
}
