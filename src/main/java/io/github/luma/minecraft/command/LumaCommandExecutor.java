package io.github.luma.minecraft.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class LumaCommandExecutor {

    private LumaCommandExecutor() {
    }

    static int execute(CommandSourceStack source, IoAction action) {
        try {
            return action.run(source);
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Lumi error: " + exception.getMessage()));
            return 0;
        }
    }

    @FunctionalInterface
    interface IoAction {
        int run(CommandSourceStack source) throws Exception;
    }
}
