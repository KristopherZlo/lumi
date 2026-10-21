package io.github.lumi.mixin.client;

import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientCommandsMixin {
    @Inject(method = "handleCommands", at = @At("TAIL"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void lumi$mergeLocalSuggestions(CallbackInfo callback) {
        var local = ClientCommandManager.getActiveDispatcher();
        if (local == null) return;
        var source = local.getRoot().getChild("lumi");
        CommandNode target = ((ClientPacketListener) (Object) this).getCommands().getRoot().getChild("lumi");
        if (source == null || target == null) return;
        // Fabric's merge can attach these children to a detached copy of the shared /lumi root.
        for (String name : new String[]{"accent", "motion"}) {
            var child = source.getChild(name);
            if (child != null) target.addChild(child);
        }
    }
}
