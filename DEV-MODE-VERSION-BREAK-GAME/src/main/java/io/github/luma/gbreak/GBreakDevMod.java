package io.github.luma.gbreak;

import io.github.luma.gbreak.block.GBreakBlocks;
import io.github.luma.gbreak.command.AnimoveCommand;
import io.github.luma.gbreak.command.CorruptCommand;
import io.github.luma.gbreak.command.GBreakCommand;
import io.github.luma.gbreak.network.GBreakNetwork;
import io.github.luma.gbreak.server.ServerBugRuntime;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GBreakDevMod implements ModInitializer {

    public static final String MOD_ID = "gbreakdev";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final ServerBugRuntime serverBugRuntime = new ServerBugRuntime();

    @Override
    public void onInitialize() {
        GBreakBlocks.register();
        GBreakNetwork.registerPayloads();
        GBreakCommand.register(this.serverBugRuntime.fakeRestoreService());
        new CorruptCommand(this.serverBugRuntime.worldCorruptionService()).register();
        new AnimoveCommand(this.serverBugRuntime.animalMoveManager()).register();
        this.serverBugRuntime.register();
        LOGGER.info("GBreak Dev initialized");
    }
}
