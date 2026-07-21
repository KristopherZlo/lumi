package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class HotkeyActionDispatcherTest {
    @Test
    void mapsEveryChordWithoutClaimingServerActionsAlreadyStarted() {
        var calls = new ArrayList<String>();
        var statuses = new ArrayList<String>();
        HotkeyActionDispatcher dispatcher = new HotkeyActionDispatcher(
                new HotkeyActionDispatcher.Actions() {
                    @Override public void openDashboard() { calls.add("dashboard"); }
                    @Override public void openSave() { calls.add("save"); }
                    @Override public void openHotkeys() { calls.add("hotkeys"); }
                    @Override public boolean undoSelection() { return false; }
                    @Override public boolean redoSelection() { return false; }
                    @Override public void undo() { calls.add("undo"); }
                    @Override public void redo() { calls.add("redo"); }
                    @Override public String toggleCompareOverlay() {
                        calls.add("compare");
                        return "luma.status.compare_overlay_hidden";
                    }
                    @Override public void quickRollback() { calls.add("rollback"); }
                    @Override public void switchBranch(int slot) {
                        calls.add("branch-" + slot);
                    }
                }, statuses::add);

        for (HotkeyActionDispatcher.Action action : HotkeyActionDispatcher.Action.values()) {
            dispatcher.dispatch(action);
        }

        assertEquals(java.util.List.of(
                "dashboard", "save", "hotkeys", "undo", "redo", "compare", "rollback"), calls);
        assertEquals(java.util.List.of(
                "luma.status.compare_overlay_hidden"), statuses);

        dispatcher.switchBranch(
                com.mojang.blaze3d.platform.InputConstants.KEY_P);
        assertEquals("branch-80", calls.getLast());
    }

    @Test
    void selectionHistoryPrecedesWorldUndoRedo() {
        var calls = new ArrayList<String>();
        var statuses = new ArrayList<String>();
        HotkeyActionDispatcher dispatcher = new HotkeyActionDispatcher(
                new HotkeyActionDispatcher.Actions() {
                    @Override public void openDashboard() { }
                    @Override public void openSave() { }
                    @Override public void openHotkeys() { }
                    @Override public boolean undoSelection() { return true; }
                    @Override public boolean redoSelection() { return true; }
                    @Override public void undo() { calls.add("world-undo"); }
                    @Override public void redo() { calls.add("world-redo"); }
                    @Override public String toggleCompareOverlay() { return ""; }
                    @Override public void quickRollback() { }
                    @Override public void switchBranch(int slot) { }
                }, statuses::add);

        dispatcher.dispatch(HotkeyActionDispatcher.Action.UNDO);
        dispatcher.dispatch(HotkeyActionDispatcher.Action.REDO);

        assertEquals(java.util.List.of(), calls);
        assertEquals(java.util.List.of(
                "luma.selection.undo", "luma.selection.redo"), statuses);
    }

    @Test
    void disabledSurvivalAccessRejectsEveryHotkeyBeforeItsAction() {
        var calls = new ArrayList<String>();
        var statuses = new ArrayList<String>();
        HotkeyActionDispatcher dispatcher = new HotkeyActionDispatcher(
                actions(calls), statuses::add, () -> false);

        dispatcher.dispatch(HotkeyActionDispatcher.Action.DASHBOARD);
        dispatcher.dispatch(HotkeyActionDispatcher.Action.UNDO);
        dispatcher.switchBranch(65);

        assertEquals(java.util.List.of(), calls);
        assertEquals(java.util.List.of(
                "luma.status.survival_disabled",
                "luma.status.survival_disabled",
                "luma.status.survival_disabled"), statuses);
    }

    private static HotkeyActionDispatcher.Actions actions(ArrayList<String> calls) {
        return new HotkeyActionDispatcher.Actions() {
            @Override public void openDashboard() { calls.add("dashboard"); }
            @Override public void openSave() { calls.add("save"); }
            @Override public void openHotkeys() { calls.add("hotkeys"); }
            @Override public boolean undoSelection() { return false; }
            @Override public boolean redoSelection() { return false; }
            @Override public void undo() { calls.add("undo"); }
            @Override public void redo() { calls.add("redo"); }
            @Override public String toggleCompareOverlay() { return "compare"; }
            @Override public void quickRollback() { calls.add("rollback"); }
            @Override public void switchBranch(int keyCode) { calls.add("branch"); }
        };
    }
}
