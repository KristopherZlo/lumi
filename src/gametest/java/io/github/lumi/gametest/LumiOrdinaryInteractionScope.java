package io.github.lumi.gametest;

import com.moulberry.axiom.Axiom;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Keeps Axiom's default empty-hand tools out of ordinary mouse interaction tests. */
final class LumiOrdinaryInteractionScope implements AutoCloseable {
    private final ClientGameTestContext context;
    private final boolean tinker;
    private final boolean bulldozer;
    private final boolean axiom = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("axiom");

    LumiOrdinaryInteractionScope(ClientGameTestContext context) {
        this.context = context;
        if (!axiom) {
            tinker = false;
            bulldozer = false;
            return;
        }
        tinker = context.computeOnClient(client -> Axiom.configuration.capabilities.tinker);
        bulldozer = context.computeOnClient(client -> Axiom.configuration.capabilities.bulldozer);
        context.runOnClient(client -> {
            Axiom.configuration.capabilities.tinker = false;
            Axiom.configuration.capabilities.bulldozer = false;
        });
    }

    @Override public void close() {
        if (!axiom) return;
        context.runOnClient(client -> {
            Axiom.configuration.capabilities.tinker = tinker;
            Axiom.configuration.capabilities.bulldozer = bulldozer;
        });
    }
}
