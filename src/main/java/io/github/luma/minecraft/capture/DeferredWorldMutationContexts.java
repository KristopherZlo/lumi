package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Utility for copying and replaying causal context on delayed vanilla mutation
 * carriers such as block events, scheduled ticks, and moving piston block
 * entities.
 */
public final class DeferredWorldMutationContexts {

    private static final ThreadLocal<Deque<AppliedFrame>> APPLIED_FRAMES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private DeferredWorldMutationContexts() {
    }

    public static void remember(Object carrier, WorldMutationSource deferredSource) {
        if (!(carrier instanceof DeferredWorldMutationContextAccess access)) {
            return;
        }
        DeferredWorldMutationContext.captureCurrent(deferredSource, currentPropagationDepth())
                .ifPresent(access::luma$setDeferredMutationContext);
    }

    public static void rememberPistonMovement(Object carrier) {
        if (!(carrier instanceof DeferredWorldMutationContextAccess access)) {
            return;
        }
        DeferredWorldMutationContext.captureCurrentForPistonMovement(currentPropagationDepth())
                .ifPresent(access::luma$setDeferredMutationContext);
    }

    public static DeferredWorldMutationContext context(Object carrier) {
        if (!(carrier instanceof DeferredWorldMutationContextAccess access)) {
            return null;
        }
        return access.luma$deferredMutationContext();
    }

    public static boolean pushSource(Object carrier) {
        DeferredWorldMutationContext context = context(carrier);
        if (context == null) {
            return false;
        }
        context.push();
        return true;
    }

    public static void clear(Object carrier) {
        if (carrier instanceof DeferredWorldMutationContextAccess access) {
            access.luma$setDeferredMutationContext(null);
        }
    }

    public static void push(Object carrier) {
        DeferredWorldMutationContext context = context(carrier);
        APPLIED_FRAMES.get().push(new AppliedFrame(context, context == null ? null : context.push()));
    }

    public static void pop() {
        Deque<AppliedFrame> frames = APPLIED_FRAMES.get();
        if (frames.isEmpty()) {
            return;
        }
        try {
            AppliedFrame frame = frames.pop();
            if (frame.sourceFrame != null) {
                frame.sourceFrame.close();
            }
        } finally {
            if (frames.isEmpty()) {
                APPLIED_FRAMES.remove();
            }
        }
    }

    private static int currentPropagationDepth() {
        Deque<AppliedFrame> frames = APPLIED_FRAMES.get();
        if (frames.isEmpty()) {
            return 0;
        }
        DeferredWorldMutationContext context = frames.peek().context();
        return context == null ? 0 : context.propagationDepth();
    }

    private record AppliedFrame(
            DeferredWorldMutationContext context,
            WorldMutationContext.SourceFrame sourceFrame
    ) {
    }
}
