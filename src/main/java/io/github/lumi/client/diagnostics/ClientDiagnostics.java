package io.github.lumi.client.diagnostics;

import io.github.lumi.client.state.ClientHistoryState;

/** Bounded read-only support summary derived from data already held by the client. */
public record ClientDiagnostics(
        String dimension,
        String workspace,
        String branch,
        int pendingKeys,
        String operation,
        String recovery,
        String worldEdit,
        String axiom,
        long usedHeapMiB) {

    public static ClientDiagnostics from(
            ClientHistoryState state,
            boolean worldEdit,
            boolean axiom,
            long usedHeapMiB) {
        var snapshot = state.snapshot().orElse(null);
        if (snapshot == null) {
            return new ClientDiagnostics(
                    "not synchronized", "-", "-", 0, "idle", "clear",
                    availability(worldEdit), availability(axiom), usedHeapMiB);
        }
        String branch = snapshot.branchName();
        int slash = branch.lastIndexOf('/');
        return new ClientDiagnostics(
                snapshot.dimensionId(),
                snapshot.workspaceName(),
                slash < 0 ? branch : branch.substring(slash + 1),
                snapshot.pendingKeys(),
                snapshot.operationActive() ? "active" : "idle",
                snapshot.recoveryPending() ? "pending" : "clear",
                availability(worldEdit),
                availability(axiom),
                Math.max(0, usedHeapMiB));
    }

    private static String availability(boolean available) {
        return available ? "available" : "unavailable";
    }
}
