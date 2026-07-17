package io.github.lumi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import io.github.lumi.LumiMod;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Groups one synchronous player command into one live action. */
@Mixin(Commands.class)
abstract class CommandsMixin {
    @WrapMethod(method = "performCommand")
    private void lumi$trackPlayerCommand(
            ParseResults<CommandSourceStack> parsed,
            String command,
            Operation<Void> original) {
        CommandSourceStack source = parsed.getContext().getSource();
        ServerPlayer player = source.getPlayer();
        var runtime = LumiMod.serverRuntime().find(source.getLevel()).orElse(null);
        if (runtime == null) {
            original.call(parsed, command);
            return;
        }
        if (!runtime.freeze().isMutationAllowed()) {
            return;
        }
        if (player == null) {
            original.call(parsed, command);
            return;
        }
        try (var ignored = DirectLiveActionContext.open(
                runtime.liveActions(), player.getUUID())) {
            original.call(parsed, command);
        }
    }
}
