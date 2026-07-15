package io.github.lumi;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LumiMod implements ModInitializer {
    public static final String MOD_ID = "lumi";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Lumi V2 initialized");
    }
}
