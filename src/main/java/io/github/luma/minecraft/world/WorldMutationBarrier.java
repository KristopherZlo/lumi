package io.github.luma.minecraft.world;

import io.github.luma.debug.LumaLoadLog;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Keeps save and history application isolated from concurrent world edits. */
final class WorldMutationBarrier {

    private final Set<MinecraftServer> blockedServers = ConcurrentHashMap.newKeySet();
    private final Map<WorldOperationManager.ActiveOperation, Lease> leases = new ConcurrentHashMap<>();

    boolean blocks(ServerLevel level) {
        return level != null
                && !WorldMutationContext.captureSuppressed()
                && !WorldMutationContext.internalWorldApplyActive()
                && this.blockedServers.contains(level.getServer());
    }

    boolean active() {
        return !this.blockedServers.isEmpty();
    }

    void acquire(WorldOperationManager.ActiveOperation operation, boolean preparedApply) {
        Lease lease = this.createLease(operation.level(), operation.handle().label(), preparedApply);
        if (lease != null) {
            this.leases.put(operation, lease);
        }
    }

    void release(WorldOperationManager.ActiveOperation operation) {
        Lease lease = this.leases.remove(operation);
        if (lease != null) {
            lease.release();
        }
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
        this.blockedServers.add(server);
        LumaLoadLog.event(
                "world-op",
                "mutation-barrier-acquire",
                "label=" + label + ", alreadyFrozen=" + !restoreTickFreeze
        );
        return new Lease(level, label, restoreTickFreeze);
    }

    private final class Lease {

        private final ServerLevel level;
        private final String label;
        private final boolean restoreTickFreeze;
        private boolean released;

        private Lease(ServerLevel level, String label, boolean restoreTickFreeze) {
            this.level = level;
            this.label = label;
            this.restoreTickFreeze = restoreTickFreeze;
        }

        void release() {
            if (this.released) {
                return;
            }
            this.released = true;
            MinecraftServer server = this.level.getServer();
            WorldMutationBarrier.this.blockedServers.remove(server);
            if (this.restoreTickFreeze) {
                server.tickRateManager().setFrozen(false);
            }
            LumaLoadLog.event(
                    "world-op",
                    "mutation-barrier-release",
                    "label=" + this.label + ", restoredFrozen=" + !this.restoreTickFreeze
            );
        }
    }
}
