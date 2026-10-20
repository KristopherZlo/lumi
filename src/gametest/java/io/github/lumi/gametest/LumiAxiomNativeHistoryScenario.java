package io.github.lumi.gametest;

import com.moulberry.axiom.world_modification.BlockBuffer;
import com.moulberry.axiom.world_modification.Dispatcher;
import com.moulberry.axiom.world_modification.HistoryEntry;
import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import io.github.lumi.client.integration.AxiomWorldHistory;
import io.github.lumi.minecraft.runtime.DirectLiveActionContext;
import io.github.lumi.mixin.client.AxiomDispatcherPendingAccess;
import io.github.lumi.network.OperationEventPayload;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Exercises actual Dispatcher controls, the connected server ledger and native files. */
final class LumiAxiomNativeHistoryScenario {
    private BlockPos origin;
    private int undoCount;

    void run(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorReport report) throws IOException {
        ready(context);
        var operations = new LumiBehaviorOperations(context, world.getServer(), report);
        origin = world.getServer().computeOnServer(server -> {
            server.tickRateManager().setFrozen(true);
            var player = server.getPlayerList().getPlayers().getFirst();
            LumiAxiomGameTests.activateAxiom(player);
            return player.blockPosition().atY(170);
        });
        manual(world, origin, Blocks.STONE);
        manual(world, origin.east(4), Blocks.STONE);
        operations.awaitDurability("native_fixture");
        operations.save("native-baseline");
        context.runOnClient(client -> {
            var forwards = new BlockBuffer();
            var backwards = new BlockBuffer();
            forwards.set(origin.getX(), origin.getY(), origin.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
            backwards.set(origin.getX(), origin.getY(), origin.getZ(), Blocks.STONE.defaultBlockState());
            Dispatcher.push(new HistoryEntry<>(forwards, backwards, origin, "Native integration fixture",
                    HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME));
        });
        assertBlock(context, world, origin, Blocks.GOLD_BLOCK);
        context.waitFor(client -> LumiClient.networking().worldActions().orElseThrow().view().undo().stream()
                .anyMatch(group -> !group.axiomEntries().isEmpty()));
        if (context.computeOnClient(client -> AxiomDispatcherPendingAccess.lumi$history().getSize()) != 1)
            throw new AssertionError("Real native history was replaced by shared display rows");
        manual(world, origin.east(4), Blocks.DIAMOND_BLOCK);
        operations.awaitDurability("native_newer_manual");
        operations.nativeHistoryStep("native_selective_undo", true);
        assertBlock(context, world, origin, Blocks.STONE);
        assertBlock(context, world, origin.east(4), Blocks.DIAMOND_BLOCK);
        operations.undo("manual_after_native");
        assertBlock(context, world, origin.east(4), Blocks.STONE);
        operations.nativeHistoryStep("native_selective_redo", false);
        assertBlock(context, world, origin, Blocks.GOLD_BLOCK);
        operations.redo("manual_after_native");
        assertBlock(context, world, origin.east(4), Blocks.DIAMOND_BLOCK);
        operations.undo("manual_first");
        operations.undo("native_via_lumi");
        assertBlock(context, world, origin, Blocks.STONE);
        operations.nativeHistoryStep("native_redo_after_lumi", false);
        operations.redo("manual_second");
        assertBlock(context, world, origin, Blocks.GOLD_BLOCK);

        manual(world, origin, Blocks.EMERALD_BLOCK);
        operations.awaitDurability("native_overlap");
        ready(context);
        long serverRevision = world.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            return LumiMod.serverRuntime().find(player.level()).orElseThrow().liveActions().view(player.getUUID()).revision();
        });
        context.waitFor(client -> LumiClient.networking().worldActions().orElseThrow().view().revision() >= serverRevision);
        var before = context.computeOnClient(client -> LumiClient.networking().worldActions().orElseThrow().view());
        var events = context.computeOnClient(client -> new HashSet<>(LumiClient.history().state().events().keySet()));
        context.runOnClient(client -> Dispatcher.callAction(com.moulberry.axiom.UserAction.UNDO, null));
        context.waitFor(client -> LumiClient.history().state().events().values().stream().anyMatch(event ->
                !events.contains(event.requestId()) && event.state() != OperationEventPayload.State.ACCEPTED
                        && event.state() != OperationEventPayload.State.PROGRESS));
        boolean refused = context.computeOnClient(client -> LumiClient.history().state().events().values().stream()
                .filter(event -> !events.contains(event.requestId()))
                .noneMatch(event -> event.state() == OperationEventPayload.State.SUCCEEDED));
        if (!refused) throw new AssertionError("Native Undo silently crossed a later overlapping edit");
        assertBlock(context, world, origin, Blocks.EMERALD_BLOCK);
        if (!before.undo().equals(context.computeOnClient(client -> LumiClient.networking().worldActions().orElseThrow().view().undo())))
            throw new AssertionError("Refused native Undo advanced the canonical cursor");
        operations.undo("overlap_first");
        operations.nativeHistoryStep("native_after_overlap_removed", true);
        assertBlock(context, world, origin, Blocks.STONE);
        operations.nativeHistoryStep("native_ready_for_reopen", false);
        operations.undo("native_before_reopen");
        assertBlock(context, world, origin, Blocks.STONE);
        undoCount = context.computeOnClient(client -> LumiClient.networking().worldActions().orElseThrow().view().undo().size());
        context.runOnClient(client -> {
            var path = AxiomDispatcherPendingAccess.lumi$historyPath();
            var loaded = com.moulberry.axiom.world_modification.HistoryIO.loadHistory(path);
            if (loaded.getSize() != 1 || !(loaded.getHistoryEntry(0).forwards() instanceof BlockBuffer))
                throw new AssertionError("Native Axiom replay payload did not survive file reload");
        });
        report.event("gate", "axiom_native_controls", "succeeded", 0, 0,
                "real Dispatcher; selective Undo; both control orders; Redo; atomic overlap refusal; native files");
        report.assertNoRuntimeFailures();
    }

    void reopened(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorReport report) throws IOException {
        ready(context);
        var operations = new LumiBehaviorOperations(context, world.getServer(), report);
        operations.nativeHistoryStep("native_redo_reopened", false);
        assertBlock(context, world, origin, Blocks.GOLD_BLOCK);
        if (context.computeOnClient(client -> LumiClient.networking().worldActions().orElseThrow().view().undo().size())
                != undoCount + 1) throw new AssertionError("Reopen created an inverse action");
        nativeRegressions(context, world, operations);
        extras(context, world, report, operations);
        var before = context.computeOnClient(client -> LumiClient.networking().worldActions().orElseThrow().view());
        context.runOnClient(client -> Dispatcher.clear());
        context.waitFor(client -> Dispatcher.getHistoryDataCount() == 0);
        if (!before.undo().equals(context.computeOnClient(client -> LumiClient.networking().worldActions().orElseThrow().view().undo())))
            throw new AssertionError("Axiom Clear deleted Lumi history");
        operations.undo("lumi_after_native_clear");
        assertBlock(context, world, origin, Blocks.STONE);
        operations.redo("lumi_after_native_clear");
        assertBlock(context, world, origin, Blocks.GOLD_BLOCK);
        report.event("gate", "axiom_native_reopen", "succeeded", 0, 0,
                "verified native identity; native Redo; native Clear preserves Lumi history; no inverse action");
        var ui = new LumiUiTestDriver(context);
        ui.openTab("luma.action.settings", io.github.lumi.client.ui.LumiSettingsScreen.class);
        ui.pressUniqueButtonAfterScrolling(io.github.lumi.client.ui.LumiSettingsScreen.class, "lumi.axiom.mode");
        context.waitFor(client -> LumiClient.history().axiomSettings().orElseThrow().selected()
                == io.github.lumi.domain.model.AxiomHistoryMode.SHARED);
        if (!context.computeOnClient(client -> AxiomWorldHistory.INSTANCE.nativeMode()))
            throw new AssertionError("Mode changed during an active history session");
        ui.closeScreen(io.github.lumi.client.ui.LumiSettingsScreen.class, io.github.lumi.client.ui.LumiDashboardScreen.class);
        ui.closeScreen(io.github.lumi.client.ui.LumiDashboardScreen.class, null);
        report.assertNoRuntimeFailures();
    }

    private void extras(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorReport report,
            LumiBehaviorOperations operations) throws IOException {
        java.util.UUID id = java.util.UUID.randomUUID();
        var nativeEntry = new java.util.concurrent.atomic.AtomicReference<java.util.UUID>();
        var callbackIds = new java.util.ArrayList<>(List.of(id));
        world.getServer().runOnServer(server -> LumiAxiomGameTests.activateAxiom(server.getPlayerList().getPlayers().getFirst()));
        context.runOnClient(client -> {
            try (var ignored = io.github.lumi.client.integration.AxiomHistoryCaptureScope.open()) {
                nativeEntry.set(io.github.lumi.client.integration.AxiomHistoryCaptureScope.current().identity().entry());
                Dispatcher.push(new HistoryEntry<>(new BlockBuffer(),
                        new BlockBuffer(), origin, "Native entity fixture", 0,
                        HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME,
                        new com.moulberry.axiom.world_modification.undo.DeleteEntityAdditionalUndoOperation(callbackIds)));
                var nbt = new net.minecraft.nbt.CompoundTag();
                nbt.putString("id", "minecraft:armor_stand"); nbt.putBoolean("NoGravity", true);
                new com.moulberry.axiom.packets.AxiomServerboundSpawnEntity(List.of(
                        new com.moulberry.axiom.packets.AxiomServerboundSpawnEntity.SpawnEntry(id,
                                net.minecraft.world.phys.Vec3.atCenterOf(origin.above(2)), 0, 0, null, nbt))).send();
            }
        });
        awaitServer(context, () -> world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level().getEntity(id) != null));
        context.waitFor(client -> LumiClient.networking().worldActions().orElseThrow().view().undo().stream()
                .anyMatch(group -> group.axiomEntries().contains(nativeEntry.get())));
        operations.nativeHistoryStep("native_entity_undo", true);
        if (world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level().getEntity(id) != null))
            throw new AssertionError("Native entity Undo left the entity behind");
        operations.nativeHistoryStep("native_entity_redo", false);
        if (world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level().getEntity(id) == null))
            throw new AssertionError("Native entity Redo lost its UUID");
        operations.undo("native_entity_via_lumi");
        if (callbackIds.size() != 1) throw new AssertionError("Server replay consumed native entity callback data");
        var before = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level()
                .getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2).unwrapKey().orElseThrow());
        var biome = before.equals(net.minecraft.world.level.biome.Biomes.DESERT)
                ? net.minecraft.world.level.biome.Biomes.PLAINS : net.minecraft.world.level.biome.Biomes.DESERT;
        java.util.UUID biomeEntry = context.computeOnClient(client -> {
          try (var ignored = io.github.lumi.client.integration.AxiomHistoryCaptureScope.open()) {
            var forwards = new com.moulberry.axiom.world_modification.BiomeBuffer();
            var backwards = new com.moulberry.axiom.world_modification.BiomeBuffer();
            forwards.set(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2, biome);
            backwards.set(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2, before);
            Dispatcher.push(new HistoryEntry<>(forwards, backwards, origin, "Native biome fixture", HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME));
            return io.github.lumi.client.integration.AxiomHistoryCaptureScope.current().identity().entry();
          }
        });
        awaitServer(context, () -> world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level()
                .getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2).is(biome)));
        context.waitFor(client -> LumiClient.networking().worldActions().orElseThrow().view().undo().stream()
                .anyMatch(group -> group.axiomEntries().contains(biomeEntry)));
        operations.nativeHistoryStep("native_biome_undo", true);
        if (!world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level()
                .getNoiseBiome(origin.getX() >> 2, origin.getY() >> 2, origin.getZ() >> 2).is(before)))
            throw new AssertionError("Native biome Undo differs");
        operations.redo("native_biome_via_lumi");
        operations.nativeHistoryStep("native_biome_final_undo", true);
        report.event("gate", "axiom_native_entities_biomes", "succeeded", 0, 0, "native gesture identity; UUID; callback retained; both controls");
    }

    private void nativeRegressions(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorOperations operations) throws IOException {
        nativeControlMask(context, world, operations);
        var growth = origin.north(2);
        world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().level().setBlockAndUpdate(growth, Blocks.DIRT.defaultBlockState()));
        var growthEntry = context.computeOnClient(client -> {
            var forwards = new BlockBuffer(); var backwards = new BlockBuffer();
            forwards.set(growth.getX(), growth.getY(), growth.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
            backwards.set(growth.getX(), growth.getY(), growth.getZ(), Blocks.DIRT.defaultBlockState());
            var entry = new HistoryEntry<com.moulberry.axiom.world_modification.BlockOrBiomeBuffer>(forwards, backwards, growth, "Native natural growth fixture", HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME);
            Dispatcher.push(entry);
            return io.github.lumi.client.integration.AxiomNativeHistoryFiles.binding(entry).entry();
        });
        assertBlock(context, world, growth, Blocks.GOLD_BLOCK);
        awaitPublished(context, growthEntry);
        operations.nativeHistoryStep("native_growth_undo", true);
        world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().level().setBlockAndUpdate(growth, Blocks.GRASS_BLOCK.defaultBlockState()));
        operations.redo("native_growth_via_lumi");
        assertBlock(context, world, growth, Blocks.GOLD_BLOCK);
        operations.nativeHistoryStep("native_growth_final_undo", true);
        var positions = java.util.stream.IntStream.range(0, 66).mapToObj(index ->
                new BlockPos(origin.getX() + 48 + (index % 3) * 16, 128 + (index / 9) * 16, origin.getZ() + (index / 3 % 3) * 16)).toList();
        world.getServer().runOnServer(server -> {
            var level = server.getPlayerList().getPlayers().getFirst().level();
            for (var pos : positions) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        });
        var largeEntry = context.computeOnClient(client -> {
            var forwards = new BlockBuffer(); var backwards = new BlockBuffer();
            for (var pos : positions) {
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    forwards.set(pos.getX() + x, pos.getY(), pos.getZ() + z, Blocks.GOLD_BLOCK.defaultBlockState());
                    backwards.set(pos.getX() + x, pos.getY(), pos.getZ() + z, Blocks.AIR.defaultBlockState());
                }
            }
            var entry = new HistoryEntry<com.moulberry.axiom.world_modification.BlockOrBiomeBuffer>(forwards, backwards, positions.getFirst(), "Native 66 sections", HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME);
            Dispatcher.push(entry);
            return io.github.lumi.client.integration.AxiomNativeHistoryFiles.binding(entry).entry();
        });
        assertBlock(context, world, positions.getLast(), Blocks.GOLD_BLOCK);
        awaitPublished(context, largeEntry);
        operations.nativeHistoryStep("native_large_undo", true);
        for (var pos : positions) assertBlock(context, world, pos, Blocks.AIR);
        operations.nativeHistoryStep("native_large_redo", false);
        for (var pos : positions) assertBlock(context, world, pos, Blocks.GOLD_BLOCK);
        operations.nativeHistoryStep("native_large_final_undo", true);
    }

    private void nativeControlMask(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorOperations operations) throws IOException {
        var position = origin.east(24);
        for (boolean editorOnly : new boolean[] {false, true}) {
            var entry = context.computeOnClient(client -> {
                var forwards = new BlockBuffer(); var backwards = new BlockBuffer();
                forwards.set(position.getX(), position.getY(), position.getZ(),
                        (editorOnly ? Blocks.DIAMOND_BLOCK : Blocks.GOLD_BLOCK).defaultBlockState());
                backwards.set(position.getX(), position.getY(), position.getZ(),
                        (editorOnly ? Blocks.GOLD_BLOCK : Blocks.AIR).defaultBlockState());
                var history = new HistoryEntry<com.moulberry.axiom.world_modification.BlockOrBiomeBuffer>(
                        forwards, backwards, position, "Native control mask fixture",
                        editorOnly ? 0 : HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME);
                Dispatcher.push(history);
                return io.github.lumi.client.integration.AxiomNativeHistoryFiles.binding(history).entry();
            });
            assertBlock(context, world, position, editorOnly ? Blocks.DIAMOND_BLOCK : Blocks.GOLD_BLOCK);
            awaitPublished(context, entry);
        }
        assertGameControlStops(context, true);
        assertBlock(context, world, position, Blocks.DIAMOND_BLOCK);
        operations.undo("editor_entry_via_lumi");
        assertBlock(context, world, position, Blocks.GOLD_BLOCK);
        assertGameControlStops(context, false);
        context.runOnClient(client -> com.moulberry.axiom.editor.EditorUI.enable());
        context.waitFor(client -> com.moulberry.axiom.editor.EditorUI.isActive());
        try {
            operations.nativeHistoryStep("editor_entry_redo", false);
            assertBlock(context, world, position, Blocks.DIAMOND_BLOCK);
            operations.nativeHistoryStep("editor_entry_undo", true);
            assertBlock(context, world, position, Blocks.GOLD_BLOCK);
        } finally {
            context.runOnClient(client -> com.moulberry.axiom.editor.EditorUI.disable());
        }
        context.waitFor(client -> !com.moulberry.axiom.editor.EditorUI.isActive());
        operations.nativeHistoryStep("game_entry_after_editor_undo", true);
        assertBlock(context, world, position, Blocks.AIR);
    }

    private static void assertGameControlStops(ClientGameTestContext context, boolean undo) {
        context.runOnClient(client -> {
            var before = AxiomWorldHistory.INSTANCE.visibleHistory().getPosition();
            if (com.moulberry.axiom.editor.EditorUI.isActive())
                throw new AssertionError("In-game control fixture has an active editor");
            Dispatcher.callAction(undo ? com.moulberry.axiom.UserAction.UNDO : com.moulberry.axiom.UserAction.REDO, null);
            if (LumiClient.networking().worldActionPending()
                    || before != AxiomWorldHistory.INSTANCE.visibleHistory().getPosition())
                throw new AssertionError("In-game Axiom control skipped an editor-only entry");
        });
    }

    private static void awaitPublished(ClientGameTestContext context, java.util.UUID entry) {
        context.waitFor(client -> LumiClient.networking().worldActions().orElseThrow().view().undo().stream()
                .anyMatch(group -> group.axiomEntries().contains(entry)));
    }

    void sharedReopened(ClientGameTestContext context, TestSingleplayerContext world, LumiBehaviorReport report) throws IOException {
        var ui = new LumiUiTestDriver(context); ui.completeOnboardingIfShown(); ui.awaitHistory();
        context.waitFor(client -> AxiomWorldHistory.INSTANCE.shared() && !AxiomClientWorkBarrier.isPaused());
        var operations = new LumiBehaviorOperations(context, world.getServer(), report);
        manual(world, origin.east(4), Blocks.EMERALD_BLOCK);
        operations.awaitDurability("shared_manual");
        operations.nativeHistoryStep("shared_native_undo_manual", true);
        assertBlock(context, world, origin.east(4), Blocks.DIAMOND_BLOCK);
        operations.nativeHistoryStep("shared_native_redo_manual", false);
        assertBlock(context, world, origin.east(4), Blocks.EMERALD_BLOCK);
        var column = origin.atY(32);
        for (int section = 0; section < 9; section++) manual(world, column.above(section * 16), Blocks.STONE);
        context.runOnClient(client -> {
            var forwards = new BlockBuffer();
            var backwards = new BlockBuffer();
            for (int section = 0; section < 9; section++) {
                var position = column.above(section * 16);
                forwards.set(position.getX(), position.getY(), position.getZ(), Blocks.GOLD_BLOCK.defaultBlockState());
                backwards.set(position.getX(), position.getY(), position.getZ(), Blocks.STONE.defaultBlockState());
            }
            Dispatcher.push(new HistoryEntry<>(forwards, backwards, column, "Shared disjoint fixture",
                    HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME));
        });
        assertBlock(context, world, column.above(128), Blocks.GOLD_BLOCK);
        world.getServer().runOnServer(server -> {
            var level = server.getPlayerList().getPlayers().getFirst().level();
            level.setBlockAndUpdate(column.east(), Blocks.BARREL.defaultBlockState());
            ((net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(column.east()))
                    .setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
        });
        operations.awaitDurability("shared_disjoint");
        operations.nativeHistoryStep("shared_nine_sections_undo", true);
        for (int section = 0; section < 9; section++) assertBlock(context, world, column.above(section * 16), Blocks.STONE);
        operations.nativeHistoryStep("shared_nine_sections_redo", false);
        for (int section = 0; section < 9; section++) assertBlock(context, world, column.above(section * 16), Blocks.GOLD_BLOCK);
        if (!world.getServer().computeOnServer(server -> {
            var item = ((net.minecraft.world.level.block.entity.BarrelBlockEntity)
                    server.getPlayerList().getPlayers().getFirst().level().getBlockEntity(column.east())).getItem(0);
            return item.is(net.minecraft.world.item.Items.DIAMOND) && item.getCount() == 7;
        }))
            throw new AssertionError("Shared replay changed disjoint block entity data");
        var nbt = origin.east(8);
        world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().level()
                .setBlockAndUpdate(nbt, Blocks.AIR.defaultBlockState()));
        manual(world, nbt, Blocks.BARREL);
        operations.undo("scoped_nbt_undo");
        assertBlock(context, world, nbt, Blocks.AIR);
        operations.redo("scoped_nbt_redo");
        assertBlock(context, world, nbt, Blocks.BARREL);
        var entity = world.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                var pig = java.util.Objects.requireNonNull(net.minecraft.world.entity.EntityType.PIG.create(
                        player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND));
                pig.setNoAi(true);
                pig.setPersistenceRequired();
                pig.setNoGravity(true);
                pig.snapTo(origin.getX() + 8.5, origin.getY() + 2, origin.getZ() + 0.5, 0, 0);
                player.level().addFreshEntity(pig);
                return pig.getUUID();
            }
        });
        operations.undo("scoped_entity_undo");
        if (world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level().getEntity(entity) != null))
            throw new AssertionError("Scoped Undo left the entity behind");
        operations.redo("scoped_entity_redo");
        if (world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level().getEntity(entity) == null))
            throw new AssertionError("Scoped Redo lost the entity UUID");
        report.event("gate", "scoped_action_replay", "succeeded", 0, 0, "nine sections; disjoint NBT; local NBT and entity Undo/Redo");
        report.event("gate", "axiom_history_setting", "succeeded", 0, 0, "real settings button; deferred apply; persisted SHARED mode; native controls undo manual edit");
    }

    private static void ready(ClientGameTestContext context) {
        var ui = new LumiUiTestDriver(context);
        ui.completeOnboardingIfShown(); ui.awaitHistory();
        // The automated client skips Axiom's welcome/enable UI; use its real history-load entry point.
        context.runOnClient(client -> {
            if (Dispatcher.historyIdentifier == null) {
                Dispatcher.tryLoadHistory("lumi-native-test-" + client.getSingleplayerServer().getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().toString().hashCode());
                AxiomDispatcherPendingAccess.lumi$applyPendingHistory();
            }
        });
        context.waitFor(client -> AxiomWorldHistory.INSTANCE.nativeMode() && AxiomWorldHistory.INSTANCE.admissionReady()
                && !AxiomClientWorkBarrier.isPaused() && !LumiClient.networking().worldActionPending());
    }

    private static void manual(TestSingleplayerContext world, BlockPos position, Block block) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var runtime = LumiMod.serverRuntime().find(player.level()).orElseThrow();
            try (var ignored = DirectLiveActionContext.open(runtime.liveActions(), player.getUUID())) {
                player.level().setBlockAndUpdate(position, block.defaultBlockState());
            }
        });
    }

    private static void assertBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos position, Block expected) {
        for (int tick = 0; tick < 200; tick++) {
            if (world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level()
                    .getBlockState(position).is(expected))) return;
            context.waitTick();
        }
        throw new AssertionError("Wrong native-history world state at " + position + "; expected " + expected);
    }

    private static void awaitServer(ClientGameTestContext context, java.util.function.BooleanSupplier condition) {
        for (int tick = 0; tick < 200; tick++) {
            if (condition.getAsBoolean()) return;
            context.waitTick();
        }
        throw new AssertionError("Native edit did not reach the server");
    }
}
