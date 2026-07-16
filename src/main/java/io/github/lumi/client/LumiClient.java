package io.github.lumi.client;

import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.ui.LumiSaveScreen;
import io.github.lumi.client.ui.SaveScreenController;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client entrypoint; retained UI controllers consume this single networking facade. */
public final class LumiClient implements ClientModInitializer {
    private static final ClientHistoryStore HISTORY = new ClientHistoryStore();
    private static final LumiClientNetworking NETWORKING = new LumiClientNetworking(HISTORY);

    @Override
    public void onInitializeClient() {
        NETWORKING.register();
        new LumiHotkeys(new HotkeyActionDispatcher(
                new HotkeyActionDispatcher.Actions() {
                    @Override public void openSave() {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new LumiSaveScreen(
                                client.screen, new SaveScreenController(NETWORKING::save)));
                    }

                    @Override public void undo() { NETWORKING.undo(); }
                    @Override public void redo() { NETWORKING.redo(); }
                    @Override public void quickRollback() { NETWORKING.quickRollback(); }
                }, LumiClient::showFeedback)).register();
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

    public static ClientHistoryStore history() {
        return HISTORY;
    }

    public static LumiClientNetworking networking() {
        return NETWORKING;
    }
}
