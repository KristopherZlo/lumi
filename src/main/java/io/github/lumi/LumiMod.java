package io.github.lumi;

import io.github.lumi.minecraft.runtime.LumiServerRuntime;
import io.github.lumi.minecraft.runtime.LumiCommands;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LumiMod implements ModInitializer {
    public static final String MOD_ID = "lumi";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final LumiServerRuntime SERVER_RUNTIME = new LumiServerRuntime();

    @Override
    public void onInitialize() {
        SERVER_RUNTIME.registerEvents();
        LumiCommands.register();
        LOGGER.info("Lumi V2 initialized");
    }

    public static LumiServerRuntime serverRuntime() {
        return SERVER_RUNTIME;
    }
}
