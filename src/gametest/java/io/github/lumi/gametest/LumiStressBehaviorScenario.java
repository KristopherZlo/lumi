package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/** Large TNT, crystal and durable-entity history stress workflow. */
final class LumiStressBehaviorScenario {
    private static final long RANDOM_SEED = 710L;

    private final ClientGameTestContext context;
    private final LumiBehaviorReport report;
    private final LumiBehaviorChecks checks;
    private final LumiBehaviorOperations operations;
    private final LumiBehaviorActions actions;
    private final BlockPos origin;
    private final List<BlockPos> tnt;
    private final List<BlockBox> area;

    LumiStressBehaviorScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.context = context;
        this.report = report;
        checks = new LumiBehaviorChecks(context, singleplayer, report);
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        actions = new LumiBehaviorActions(singleplayer.getServer(), report);
        WorldOrigin world = singleplayer.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            return new WorldOrigin(
                    player.blockPosition(), player.level().getMinY(),
                    player.level().getMaxY() - 1);
        });
        origin = world.position();
        tnt = actions.planTntGrid128(origin);
        int minX = tnt.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minY = tnt.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minZ = tnt.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxX = tnt.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maxY = tnt.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maxZ = tnt.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        area = List.of(new BlockBox(
                minX - 24, Math.max(world.minY(), minY - 24), minZ - 24,
                maxX + 24, Math.min(world.maxY(), maxY + 24), maxZ + 24));
    }

    void run() throws IOException {
        checks.snapshot("stress_world_preload", area);
        checks.waitTicks("stress_world_stabilization", 100);
        checks.snapshot("stress_initial", area);

        Map<Integer, LumiWorldSnapshot> placedStates = placeTntOverFourSeconds();
        LumiWorldSnapshot allTnt = placedStates.get(128);
        BlockPos ignition = tnt.get(new Random(RANDOM_SEED).nextInt(tnt.size()));
        actions.igniteTnt("ignite_128_tnt", ignition);
        checks.waitTicks("partial_128_tnt_explosion_6s", 120);
        LumiWorldSnapshot partialExplosion = checks.snapshot(
                "tnt_128_partial_explosion", area);

        operations.undo("tnt_128_partial_explosion");
        checks.assertSnapshot("undo_tnt_128_partial_explosion", area, allTnt);
        operations.redo("tnt_128_partial_explosion");
        checks.assertSnapshot("redo_tnt_128_partial_explosion", area, partialExplosion);

        for (int undo = 1; undo <= 5; undo++) {
            operations.undo("tnt_128_repeat_" + undo);
            int expectedCount = undo == 1 ? 128 : 129 - undo;
            checks.assertSnapshot("undo_tnt_128_repeat_" + undo,
                    area, placedStates.get(expectedCount));
        }
        for (int redo = 1; redo <= 4; redo++) {
            operations.redo("tnt_128_rebuild_" + redo);
            checks.assertSnapshot("redo_tnt_128_rebuild_" + redo,
                    area, placedStates.get(124 + redo));
        }

        runEndCrystal(allTnt);
        checks.finish();
    }

    private Map<Integer, LumiWorldSnapshot> placeTntOverFourSeconds()
            throws IOException {
        long started = System.nanoTime();
        int placed = 0;
        Map<Integer, LumiWorldSnapshot> states = new LinkedHashMap<>();
        try {
            for (int tick = 0; tick < 80; tick++) {
                int targetCount = (tick + 1) * tnt.size() / 80;
                while (placed < targetCount) {
                    actions.placeTnt(tnt.get(placed++));
                    if (placed >= 124) {
                        states.put(placed, checks.snapshot(
                                "tnt_placement_" + placed, area));
                    }
                }
                context.waitTick();
            }
            report.event("change", "place_128_tnt_over_4s", "succeeded",
                    80, elapsedMillis(started), "placed=128 spacing=1");
            return Map.copyOf(states);
        } catch (IOException | RuntimeException | Error failed) {
            report.event("change", "place_128_tnt_over_4s", "failed",
                    0, elapsedMillis(started), failed.toString());
            throw failed;
        }
    }

    private void runEndCrystal(LumiWorldSnapshot allTnt) throws IOException {
        BlockPos obsidian = actions.surfacePosition(
                origin.getX() + 20, origin.getZ());
        actions.placeBlocks("place_crystal_obsidian",
                Items.OBSIDIAN, List.of(obsidian));
        LumiWorldSnapshot obsidianOnly = checks.snapshot(
                "crystal_obsidian_only", area);
        var crystal = actions.placeEndCrystal(obsidian);
        LumiWorldSnapshot crystalPlaced = checks.snapshot(
                "end_crystal_placed", area);

        actions.attackEntity("punch_end_crystal", crystal, Items.AIR);
        checks.waitTicks("punched_end_crystal_explosion_2s", 40);
        checks.snapshot("punched_end_crystal_exploded", area);
        operations.undo("punched_end_crystal");
        checks.assertSnapshot("undo_punched_end_crystal", area, crystalPlaced);

        actions.shootEndCrystal(crystal);
        checks.waitTicks("arrow_end_crystal_explosion_1s", 20);
        checks.snapshot("arrow_end_crystal_exploded", area);
        operations.undo("arrow_end_crystal");
        checks.assertSnapshot("undo_arrow_end_crystal", area, crystalPlaced);
        operations.undo("remove_end_crystal");
        checks.assertSnapshot("undo_end_crystal_placement", area, obsidianOnly);
        operations.undo("remove_crystal_obsidian");
        checks.assertSnapshot("undo_crystal_obsidian", area, allTnt);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record WorldOrigin(BlockPos position, int minY, int maxY) { }
}
