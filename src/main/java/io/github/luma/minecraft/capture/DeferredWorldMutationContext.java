package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Optional;

/**
 * Immutable causal identity copied onto vanilla objects that apply their world
 * changes later than the player action that created them.
 */
public record DeferredWorldMutationContext(
        WorldMutationSource source,
        String actor,
        String actionId,
        boolean accessAllowed,
        int propagationDepth
) {

    static final int MAX_MECHANISM_PROPAGATION_DEPTH = 2;

    public DeferredWorldMutationContext {
        source = source == null ? WorldMutationSource.SYSTEM : source;
        actor = actor == null || actor.isBlank() ? HistoryCaptureManager.defaultActor(source) : actor;
        actionId = actionId == null ? "" : actionId;
        propagationDepth = Math.max(0, propagationDepth);
    }

    public static Optional<DeferredWorldMutationContext> captureCurrent(WorldMutationSource deferredSource) {
        return captureCurrent(deferredSource, 0);
    }

    static Optional<DeferredWorldMutationContext> captureCurrent(WorldMutationSource deferredSource, int parentDepth) {
        return captureCurrent(deferredSource, parentDepth, true);
    }

    static Optional<DeferredWorldMutationContext> captureCurrentForPistonMovement(int parentDepth) {
        return captureCurrent(WorldMutationSource.PISTON, parentDepth, false);
    }

    private static Optional<DeferredWorldMutationContext> captureCurrent(
            WorldMutationSource deferredSource,
            int parentDepth,
            boolean incrementMechanismDepth
    ) {
        String actionId = WorldMutationContext.currentActionId();
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }

        WorldMutationSource currentSource = WorldMutationContext.currentSource();
        if (!HistoryCaptureManager.shouldCaptureMutation(currentSource)) {
            return Optional.empty();
        }

        WorldMutationSource source = deferredSource == null ? currentSource : deferredSource;
        if (!HistoryCaptureManager.shouldCaptureMutation(source)) {
            return Optional.empty();
        }
        int propagationDepth = incrementMechanismDepth
                ? Math.max(0, parentDepth) + 1
                : Math.max(1, parentDepth);
        if (isMechanismSource(source) && propagationDepth > MAX_MECHANISM_PROPAGATION_DEPTH) {
            return Optional.empty();
        }

        return Optional.of(new DeferredWorldMutationContext(
                source,
                WorldMutationContext.currentActor(),
                actionId,
                WorldMutationContext.currentAccessAllowed(),
                propagationDepth
        ));
    }

    public boolean hasAction() {
        return this.actionId != null && !this.actionId.isBlank();
    }

    public WorldMutationContext.SourceFrame push() {
        return WorldMutationContext.pushSource(this.source, this.actor, this.actionId, this.accessAllowed);
    }

    private static boolean isMechanismSource(WorldMutationSource source) {
        return source == WorldMutationSource.BLOCK_UPDATE || source == WorldMutationSource.PISTON;
    }
}
