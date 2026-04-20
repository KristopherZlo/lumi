package io.github.luma;

import io.github.luma.minecraft.command.LumaCommands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LumaMod implements ModInitializer {

    public static final String MOD_ID = "luma";
    public static final String MOD_NAME = "Luma";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    private final LumaCommands commands = new LumaCommands();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> this.commands.register(dispatcher));
        LOGGER.info("{} bootstrap initialized", MOD_NAME);
    }
}
