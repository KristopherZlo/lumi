package io.github.luma.gbreak.client;

import net.fabricmc.api.ClientModInitializer;

public final class GBreakDevClient implements ClientModInitializer {

    private final ClientBugRuntime clientBugRuntime = new ClientBugRuntime();
    private final LumiDemoClientRuntime lumiDemoClientRuntime = new LumiDemoClientRuntime();

    @Override
    public void onInitializeClient() {
        this.clientBugRuntime.register();
        this.lumiDemoClientRuntime.register();
    }
}
