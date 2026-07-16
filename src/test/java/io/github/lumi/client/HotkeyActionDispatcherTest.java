package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class HotkeyActionDispatcherTest {
    @Test
    void mapsEveryChordToOneUserIntentAndImmediateStatus() {
        var calls = new ArrayList<String>();
        var statuses = new ArrayList<String>();
        HotkeyActionDispatcher dispatcher = new HotkeyActionDispatcher(
                new HotkeyActionDispatcher.Actions() {
                    @Override public void openSave() { calls.add("save"); }
                    @Override public void undo() { calls.add("undo"); }
                    @Override public void redo() { calls.add("redo"); }
                    @Override public void quickRollback() { calls.add("rollback"); }
                }, statuses::add);

        for (HotkeyActionDispatcher.Action action : HotkeyActionDispatcher.Action.values()) {
            dispatcher.dispatch(action);
        }

        assertEquals(java.util.List.of("save", "undo", "redo", "rollback"), calls);
        assertEquals(java.util.List.of(
                "luma.status.undo_started", "luma.status.redo_started",
                "luma.status.quick_rollback_started"), statuses);
    }
}
