package io.github.lumi.client;

import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientHistoryStore;
import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint; retained UI controllers consume this single networking facade. */
public final class LumiClient implements ClientModInitializer {
    private static final ClientHistoryStore HISTORY = new ClientHistoryStore();
    private static final LumiClientNetworking NETWORKING = new LumiClientNetworking(HISTORY);

    @Override
    public void onInitializeClient() {
        NETWORKING.register();
        LumiMod.LOGGER.info("Lumi V2 client initialized");
    }

    public static ClientHistoryStore history() {
        return HISTORY;
    }

    public static LumiClientNetworking networking() {
        return NETWORKING;
    }
}
