package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.ui.LumiZoneDetailsScreen;
import io.github.lumi.client.ui.LumiZonesScreen;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.OperationPriority;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import io.github.lumi.network.OperationEventPayload;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Negative interaction contracts against real client input and server packets. */
@SuppressWarnings("UnstableApiUsage")
final class LumiZoneUiContract {
    private final ClientGameTestContext context;
    private final TestSingleplayerContext world;
    private final LumiUiTestDriver ui;

    LumiZoneUiContract(ClientGameTestContext context, TestSingleplayerContext world, LumiUiTestDriver ui) {
        this.context = context;
        this.world = world;
        this.ui = ui;
    }

    void verifySwordHistory(BlockPos edited) {
        var server = world.getServer();
        ItemStack previous = server.computeOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            var original = player.getMainHandItem().copy();
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
            return original;
        });
        try {
            await(() -> context.computeOnClient(client ->
                    client.player.getMainHandItem().is(Items.WOODEN_SWORD)), "Sword did not synchronize");
            context.runOnClient(client -> {
                var snapshot = LumiClient.history().state().snapshot().orElseThrow();
                var selection = LumiClient.selection();
                selection.reset();
                selection.activate(snapshot.workspaceId(), snapshot.dimensionId());
                selection.setFirst(new BlockPosition(edited.getX(), edited.getY(), edited.getZ()));
                selection.setSecond(new BlockPosition(edited.getX() + 1, edited.getY(), edited.getZ()));
            });
            for (int attempt = 0; attempt < 3; attempt++) {
                ui.completeOnboardingIfShown();
                ui.pressChord("key.lumi.undo");
            }
            for (int tick = 0; tick < 20; tick++) context.waitTick();
            if (!context.computeOnClient(client -> LumiClient.selection().bounds().isEmpty())) {
                throw new AssertionError("Sword Undo did not exhaust the local selection");
            }
            assertEditIntact(edited);
            for (int attempt = 0; attempt < 3; attempt++) {
                ui.completeOnboardingIfShown();
                ui.pressChord("key.lumi.redo");
            }
            for (int tick = 0; tick < 20; tick++) context.waitTick();
            assertEditIntact(edited);
        } finally {
            context.runOnClient(client -> LumiClient.selection().reset());
            server.runOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst()
                    .setItemSlot(EquipmentSlot.MAINHAND, previous));
        }
    }

    void verifyEnterRefusalAndRetry(String name) {
        var server = world.getServer();
        Set<java.util.UUID> before = context.computeOnClient(client ->
                Set.copyOf(LumiClient.history().state().events().keySet()));
        var hold = new MetadataHold();
        server.runOnServer(minecraft -> runtime(minecraft).operations()
                .enqueue(hold, OperationPriority.NORMAL, ignored -> { }));
        try {
            ui.pressUniqueButton(LumiZonesScreen.class, "luma.zones.enter");
            await(() -> context.computeOnClient(client -> LumiClient.history().state().events().values()
                    .stream().anyMatch(event -> !before.contains(event.requestId())
                            && event.state() == OperationEventPayload.State.FAILED
                            && event.message().contains("Zone metadata cannot change during an operation"))),
                    "Enter did not receive the server's metadata refusal");
            ui.requireScreen(LumiZonesScreen.class);
            ui.assertButtonEventually(LumiZonesScreen.class, "luma.zones.enter", true);
            ui.awaitZone(name, false);
        } finally {
            hold.released = true;
        }
        awaitIdle();
        ui.pressUniqueButton(LumiZonesScreen.class, "luma.zones.enter");
        context.waitForScreen(LumiZoneDetailsScreen.class);
        ui.awaitZone(name, true);
        awaitIdle();
        ui.pressUniqueButton(LumiZoneDetailsScreen.class, "luma.zones.leave");
        context.waitForScreen(LumiZonesScreen.class);
        ui.awaitZone(name, false);
        ui.assertButtonEventually(LumiZonesScreen.class, "luma.zones.enter", true);
    }

    private FabricDimensionRuntime runtime(MinecraftServer server) {
        return LumiMod.serverRuntime().find(server.getPlayerList().getPlayers().getFirst().level()).orElseThrow();
    }

    private void assertEditIntact(BlockPos edited) {
        world.getServer().runOnServer(minecraft -> {
            var player = minecraft.getPlayerList().getPlayers().getFirst();
            if (!player.level().getBlockState(edited).is(Blocks.STONE)) {
                throw new AssertionError("Exhausted sword history changed the world");
            }
        });
    }

    void awaitIdle() {
        await(() -> world.getServer().computeOnServer(minecraft ->
                !runtime(minecraft).operations().hasActiveOperation()
                        && runtime(minecraft).operations().queuedCount() == 0), "Zone statistics did not finish");
    }

    private void await(BooleanSupplier ready, String failure) {
        for (int tick = 0; tick < 1_200; tick++) {
            if (ready.getAsBoolean()) return;
            context.waitTick();
        }
        throw new AssertionError(failure);
    }

    private static final class MetadataHold implements DimensionMutation {
        private volatile boolean released;
        @Override public boolean requiresFreeze() { return false; }
        @Override public void advance(long deadlineNanos) { }
        @Override public boolean isTerminal() { return released; }
        @Override public boolean isSafeToRelease() { return true; }
    }
}
