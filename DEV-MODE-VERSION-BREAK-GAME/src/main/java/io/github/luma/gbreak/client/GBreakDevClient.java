package io.github.luma.gbreak.client;

import net.fabricmc.api.ClientModInitializer;

public final class GBreakDevClient implements ClientModInitializer {

    private final ClientBugRuntime clientBugRuntime = new ClientBugRuntime();
    private final LumiDemoClientRuntime lumiDemoClientRuntime = new LumiDemoClientRuntime();
    private final CorruptionSettingsClientRuntime corruptionSettingsClientRuntime = new CorruptionSettingsClientRuntime();

    @Override
    public void onInitializeClient() {
        this.clientBugRuntime.register();
        this.lumiDemoClientRuntime.register();
        this.corruptionSettingsClientRuntime.register();
    }
}
