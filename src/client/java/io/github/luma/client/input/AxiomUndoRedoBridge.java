package io.github.luma.client.input;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.integration.axiom.AxiomNativeUndoRedoGuard;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Delegates Lumi undo/redo chords to Axiom's own history dispatcher when the
 * latest tracked action belongs to Axiom.
 */
public final class AxiomUndoRedoBridge {

    private final AxiomUndoRedoInvoker invoker;

    public AxiomUndoRedoBridge() {
        this(new ReflectiveAxiomUndoRedoInvoker());
    }

    AxiomUndoRedoBridge(AxiomUndoRedoInvoker invoker) {
        this.invoker = Objects.requireNonNull(invoker, "invoker");
    }

    public DispatchResult dispatch(boolean undo) {
        if (!this.invoker.available()) {
            return DispatchResult.fallback("Axiom dispatcher is unavailable");
        }

        try {
            int beforePosition = this.invoker.historyPosition();
            int historySize = this.invoker.historyDataCount();
            if (!canMove(undo, beforePosition, historySize)) {
                return DispatchResult.fallback("Axiom history has no " + (undo ? "undo" : "redo") + " entry");
            }

            int replayToken = AxiomNativeUndoRedoGuard.expectNativeReplay();
            boolean moved = false;
            try {
                this.invoker.dispatch(undo);
                int afterPosition = this.invoker.historyPosition();
                moved = movedExpectedDirection(undo, beforePosition, afterPosition);
                if (!moved) {
                    return DispatchResult.fallback("Axiom history position did not change");
                }
                LumaDebugLog.log(
                        "axiom-undo-redo",
                        "Dispatched native Axiom {} from history position {} to {}",
                        undo ? "undo" : "redo",
                        beforePosition,
                        afterPosition
                );
                return DispatchResult.dispatched(replayToken);
            } finally {
                if (!moved) {
                    AxiomNativeUndoRedoGuard.cancelExpectedNativeReplay(replayToken);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LumaDebugLog.log(
                    "axiom-undo-redo",
                    "Falling back to Lumi replay for Axiom {}: {}",
                    undo ? "undo" : "redo",
                    exception.toString()
            );
            return DispatchResult.fallback(exception.getMessage());
        }
    }

    private static boolean canMove(boolean undo, int position, int historySize) {
        if (undo) {
            return position >= 0;
        }
        return historySize > 0 && position < historySize - 1;
    }

    private static boolean movedExpectedDirection(boolean undo, int beforePosition, int afterPosition) {
        if (undo) {
            return afterPosition < beforePosition;
        }
        return afterPosition > beforePosition;
    }

    interface AxiomUndoRedoInvoker {

        boolean available();

        int historyPosition() throws ReflectiveOperationException;

        int historyDataCount() throws ReflectiveOperationException;

        void dispatch(boolean undo) throws ReflectiveOperationException;
    }

    public record DispatchResult(boolean dispatched, int replayToken, String fallbackReason) {

        private static DispatchResult dispatched(int replayToken) {
            return new DispatchResult(true, replayToken, "");
        }

        private static DispatchResult fallback(String reason) {
            return new DispatchResult(false, 0, reason == null ? "" : reason);
        }

        public void clearUnconsumedReplayExpectation() {
            AxiomNativeUndoRedoGuard.cancelExpectedNativeReplay(this.replayToken);
        }
    }

    private static final class ReflectiveAxiomUndoRedoInvoker implements AxiomUndoRedoInvoker {

        private static final String DISPATCHER_CLASS_NAME = "com.moulberry.axiom.world_modification.Dispatcher";
        private static final String USER_ACTION_CLASS_NAME = "com.moulberry.axiom.UserAction";

        private Methods methods;
        private boolean unavailable;

        @Override
        public boolean available() {
            try {
                return this.methods() != null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                this.unavailable = true;
                LumaDebugLog.log(
                        "axiom-undo-redo",
                        "Axiom dispatcher reflection unavailable: {}",
                        exception.toString()
                );
                return false;
            }
        }

        @Override
        public int historyPosition() throws ReflectiveOperationException {
            Method historyPosition = this.requireMethods().historyPosition();
            return ((Number) historyPosition.invoke(null, true)).intValue();
        }

        @Override
        public int historyDataCount() throws ReflectiveOperationException {
            Method historyDataCount = this.requireMethods().historyDataCount();
            return ((Number) historyDataCount.invoke(null)).intValue();
        }

        @Override
        public void dispatch(boolean undo) throws ReflectiveOperationException {
            Methods resolvedMethods = this.requireMethods();
            resolvedMethods.callAction().invoke(
                    null,
                    undo ? resolvedMethods.undoAction() : resolvedMethods.redoAction(),
                    null
            );
        }

        private Methods requireMethods() throws ReflectiveOperationException {
            Methods resolvedMethods = this.methods();
            if (resolvedMethods == null) {
                throw new ClassNotFoundException(DISPATCHER_CLASS_NAME);
            }
            return resolvedMethods;
        }

        private Methods methods() throws ReflectiveOperationException {
            if (this.unavailable) {
                return null;
            }
            if (this.methods != null) {
                return this.methods;
            }

            try {
                this.methods = this.loadMethods();
                return this.methods;
            } catch (ClassNotFoundException exception) {
                this.unavailable = true;
                return null;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private Methods loadMethods() throws ReflectiveOperationException {
            Class<?> userActionClass = Class.forName(USER_ACTION_CLASS_NAME);
            Class<Enum> userActionEnumClass = (Class<Enum>) userActionClass.asSubclass(Enum.class);
            Enum<?> undoAction = Enum.valueOf(userActionEnumClass, "UNDO");
            Enum<?> redoAction = Enum.valueOf(userActionEnumClass, "REDO");
            Class<?> dispatcherClass = Class.forName(DISPATCHER_CLASS_NAME);
            return new Methods(
                    dispatcherClass.getMethod("getHistoryPosition", boolean.class),
                    dispatcherClass.getMethod("getHistoryDataCount"),
                    dispatcherClass.getMethod("callAction", userActionClass, Object.class),
                    undoAction,
                    redoAction
            );
        }

        private record Methods(
                Method historyPosition,
                Method historyDataCount,
                Method callAction,
                Object undoAction,
                Object redoAction
        ) {
        }
    }
}
