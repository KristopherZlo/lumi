package io.github.luma.domain.model;

/**
 * Lightweight client-facing workspace state for HUD rendering.
 *
 * <p>The snapshot intentionally mirrors only the data required by the
 * persistent overlay and action bar: the current workspace identity, active
 * variant label, pending git-style counters, and the latest world operation
 * progress.
 */
public record WorkspaceHudSnapshot(
        String projectName,
        String workspaceLabel,
        String activeVariantId,
        PendingChangeSummary pendingChanges,
        OperationSnapshot operationSnapshot
) {

    public int plusCount() {
        PendingChangeSummary pending = this.pendingChanges == null ? PendingChangeSummary.empty() : this.pendingChanges;
        return pending.addedBlocks() + pending.changedBlocks();
    }

    public int minusCount() {
        PendingChangeSummary pending = this.pendingChanges == null ? PendingChangeSummary.empty() : this.pendingChanges;
        return pending.removedBlocks() + pending.changedBlocks();
    }
}
