package io.github.luma;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LumaMod implements ModInitializer {

    public static final String MOD_ID = "luma";
    public static final String MOD_NAME = "Luma";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    @Override
    public void onInitialize() {
        LOGGER.info("{} bootstrap initialized", MOD_NAME);
    }
}
