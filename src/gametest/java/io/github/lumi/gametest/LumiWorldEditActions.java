package io.github.lumi.gametest;

import com.sk89q.worldedit.fabric.FabricPermissionsProvider;
import com.sk89q.worldedit.fabric.FabricWorldEdit;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.level.ServerPlayer;

/** Loads WorldEdit fixture types only when a scenario actually uses that tool. */
final class LumiWorldEditActions {
    private final TestServerContext server;

    LumiWorldEditActions(TestServerContext server) {
        this.server = server;
    }

    void execute(List<String> commands) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            FabricPermissionsProvider previous = FabricWorldEdit.inst.getPermissionsProvider();
            FabricWorldEdit.inst.setPermissionsProvider(new FabricPermissionsProvider() {
                @Override public boolean hasPermission(ServerPlayer actor, String permission) {
                    return actor == player;
                }
                @Override public void registerPermission(String permission) { }
            });
            try {
                for (String command : commands) LumiBehaviorActions.execute(minecraft, player, command);
            } finally {
                FabricWorldEdit.inst.setPermissionsProvider(previous);
            }
        });
    }
}
