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
        boolean accessAllowed
) {

    public DeferredWorldMutationContext {
        source = source == null ? WorldMutationSource.SYSTEM : source;
        actor = actor == null || actor.isBlank() ? HistoryCaptureManager.defaultActor(source) : actor;
        actionId = actionId == null ? "" : actionId;
    }

    public static Optional<DeferredWorldMutationContext> captureCurrent(WorldMutationSource deferredSource) {
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

        return Optional.of(new DeferredWorldMutationContext(
                source,
                WorldMutationContext.currentActor(),
                actionId,
                WorldMutationContext.currentAccessAllowed()
        ));
    }

    public boolean hasAction() {
        return this.actionId != null && !this.actionId.isBlank();
    }

    public WorldMutationContext.SourceFrame push() {
        return WorldMutationContext.pushSource(this.source, this.actor, this.actionId, this.accessAllowed);
    }
}
