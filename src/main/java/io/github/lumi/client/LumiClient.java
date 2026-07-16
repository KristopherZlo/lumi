package io.github.lumi.client;

import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.client.state.ClientSelection;
import io.github.lumi.client.ui.LumiSaveScreen;
import io.github.lumi.client.ui.LumiOperationHud;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiBranchScreen;
import io.github.lumi.client.ui.LumiCompareScreen;
import io.github.lumi.client.ui.LumiMergeScreen;
import io.github.lumi.client.ui.LumiRecoveryScreen;
import io.github.lumi.client.ui.LumiRestoreScreen;
import io.github.lumi.client.ui.BranchNameController;
import io.github.lumi.client.ui.SaveScreenController;
import io.github.lumi.network.HistorySnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client entrypoint; retained UI controllers consume this single networking facade. */
public final class LumiClient implements ClientModInitializer {
    private static final ClientHistoryStore HISTORY = new ClientHistoryStore();
    private static final ClientCompareStore COMPARISONS = new ClientCompareStore();
    private static final ClientSelection SELECTION = new ClientSelection();
    private static final LumiClientNetworking NETWORKING =
            new LumiClientNetworking(HISTORY, COMPARISONS, LumiClient::showRecovery);

    @Override
    public void onInitializeClient() {
        NETWORKING.register();
        new LumiHotkeys(new HotkeyActionDispatcher(
                new HotkeyActionDispatcher.Actions() {
                    @Override public void openDashboard() {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new LumiDashboardScreen(
                                client.screen, HISTORY,
                                () -> client.setScreen(new LumiSaveScreen(
                                        client.screen,
                                        new SaveScreenController(
                                                NETWORKING::save, NETWORKING::amend))),
                                () -> client.setScreen(new LumiBranchScreen(
                                        client.screen,
                                        currentBranch(),
                                        new BranchNameController(NETWORKING::createBranch))),
                                () -> {
                                    var snapshot = HISTORY.state().snapshot().orElseThrow(
                                            () -> new IllegalStateException(
                                                    "Lumi history has not synchronized yet"));
                                    client.setScreen(new LumiMergeScreen(
                                            client.screen, snapshot.branchName(),
                                            snapshot.branches(), NETWORKING::merge));
                                },
                                NETWORKING::quickRollback,
                                version -> client.setScreen(new LumiRestoreScreen(
                                        client.screen,
                                        version.id(),
                                        version.message(),
                                        SELECTION.bounds(),
                                        (target, includeEntities) -> {
                                            if (includeEntities) {
                                                NETWORKING.restore(target);
                                            } else {
                                                NETWORKING.restoreWithoutEntities(target);
                                            }
                                        },
                                        NETWORKING::restoreArea)),
                                target -> client.setScreen(new LumiCompareScreen(
                                        client.screen,
                                        COMPARISONS,
                                        target.label(),
                                        () -> NETWORKING.compare(
                                                target.before(), target.after()),
                                        NETWORKING::cancelCompare))));
                    }

                    @Override public void openSave() {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new LumiSaveScreen(
                                client.screen, new SaveScreenController(
                                        NETWORKING::save, NETWORKING::amend)));
                    }

                    @Override public boolean undoSelection() { return SELECTION.undo(); }
                    @Override public boolean redoSelection() { return SELECTION.redo(); }
                    @Override public void undo() { NETWORKING.undo(); }
                    @Override public void redo() { NETWORKING.redo(); }
                    @Override public void quickRollback() { NETWORKING.quickRollback(); }
                    @Override public void switchBranch(int slot) {
                        var snapshot = HISTORY.state().snapshot().orElseThrow(
                                () -> new IllegalStateException(
                                        "Lumi history has not synchronized yet"));
                        if (slot >= snapshot.branches().size()) {
                            throw new IllegalStateException(
                                    "No Lumi branch is bound to this number");
                        }
                        var branch = snapshot.branches().get(slot);
                        if (!branch.active()) {
                            NETWORKING.switchBranch(branch.name());
                        }
                    }
                }, LumiClient::showFeedback)).register();
        new LumiSelectionTool(SELECTION, LumiClient::showFeedback).register();
        new LumiOperationHud(HISTORY).register();
        LumiMod.LOGGER.info("Lumi V2 client initialized");
    }

    private static void showFeedback(String value) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Component message = value.startsWith("luma.")
                ? Component.translatable(value) : Component.literal(value);
        player.displayClientMessage(message, true);
    }

    private static void showRecovery(HistorySnapshotPayload snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (!snapshot.recoveryPending()
                || snapshot.operationActive()
                || client.screen instanceof LumiRecoveryScreen) {
            return;
        }
        client.setScreen(new LumiRecoveryScreen(
                client.screen, NETWORKING::resumeRecovery, NETWORKING::returnRecovery));
    }

    private static String currentBranch() {
        String value = HISTORY.state().snapshot().orElseThrow(
                () -> new IllegalStateException("Lumi history has not synchronized yet"))
                .branchName();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    public static ClientHistoryStore history() {
        return HISTORY;
    }

    public static LumiClientNetworking networking() {
        return NETWORKING;
    }

    public static ClientSelection selection() {
        return SELECTION;
    }
}
