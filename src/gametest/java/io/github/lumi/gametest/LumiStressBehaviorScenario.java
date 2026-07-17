package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
        runSavedHistoryAndEntities(allTnt);
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

    private void runSavedHistoryAndEntities(LumiWorldSnapshot allTnt)
            throws IOException {
        CommitId tntCommit = operations.save("tnt");
        checks.assertSnapshot("save_tnt", area, allTnt);
        actions.igniteTnt("ignite_all_128_tnt",
                tnt.get(new Random(RANDOM_SEED).nextInt(tnt.size())));
        checks.waitUntil("all_128_tnt_exploded", 600,
                () -> !actions.hasTnt(tnt));
        checks.waitTicks("all_128_tnt_settle", 20);
        LumiWorldSnapshot afterTnt = checks.snapshot("after_all_tnt", area);
        CommitId afterTntCommit = operations.save("after tnt");
        checks.assertSnapshot("save_after_tnt", area, afterTnt);

        restoreAndAssert("spam_tnt_1", tntCommit, allTnt);
        restoreAndAssert("spam_after_tnt_1", afterTntCommit, afterTnt);
        restoreAndAssert("spam_tnt_2", tntCommit, allTnt);
        restoreAndAssert("spam_after_tnt_2", afterTntCommit, afterTnt);
        restoreAndAssert("spam_tnt_3", tntCommit, allTnt);
        restoreAndAssert("spam_after_tnt_3", afterTntCommit, afterTnt);
        restoreAndAssert("spam_tnt_4", tntCommit, allTnt);

        actions.destroyBlocks("break_tnt_before_quick_reset",
                List.of(tnt.get(0), tnt.get(1)));
        checks.snapshot("tnt_manually_broken", area);
        operations.quickRollback("broken_tnt");
        checks.assertSnapshot("quick_reset_broken_tnt", area, allTnt);
        restoreAndAssert("after_quick_reset", afterTntCommit, afterTnt);

        List<BlockPos> smoothPositions = randomSurfacePositions(
                RANDOM_SEED, 10, Set.of());
        actions.placeBlocks("place_10_smooth_stone",
                Items.SMOOTH_STONE, smoothPositions);
        LumiWorldSnapshot smooth = checks.snapshot("ten_smooth_stone", area);
        operations.save("smooth");
        checks.assertSnapshot("save_smooth", area, smooth);
        operations.quickRollback("clean_smooth");
        checks.assertSnapshot("quick_restore_clean_noop", area, smooth);

        List<BlockPos> glassPositions = randomSurfacePositions(
                RANDOM_SEED + 1, 10, new HashSet<>(smoothPositions));
        actions.placeBlocks("place_10_glass", Items.GLASS, glassPositions);
        checks.snapshot("ten_glass_after_smooth", area);
        restoreAndAssert("glass_to_tnt", tntCommit, allTnt);

        runArmorStandAndChickenChecks();
    }

    private void runArmorStandAndChickenChecks() throws IOException {
        List<BlockPos> standPositions = List.of(
                actions.surfacePosition(origin.getX() + 24, origin.getZ() - 3),
                actions.surfacePosition(origin.getX() + 24, origin.getZ()),
                actions.surfacePosition(origin.getX() + 24, origin.getZ() + 3));
        List<UUID> stands = actions.placeArmorStands(standPositions);
        actions.equipArmorStands(stands, RANDOM_SEED);
        LumiWorldSnapshot armoredStands = checks.snapshot(
                "three_armored_stands", area);

        actions.attackEntity("break_armored_stand", stands.getFirst(), Items.AIR);
        checks.waitUntil("armored_stand_removed", 20,
                () -> !actions.hasEntity(stands.getFirst()));
        checks.snapshot("one_armored_stand_broken", area);
        operations.undo("broken_armored_stand");
        checks.assertSnapshot("undo_broken_armored_stand", area, armoredStands);

        BlockPos standTnt = actions.surfacePosition(
                origin.getX() + 21, origin.getZ());
        actions.placeTnt(standTnt);
        LumiWorldSnapshot standsWithTnt = checks.snapshot(
                "armored_stands_with_tnt", area);
        actions.igniteTnt("ignite_armor_stand_tnt", standTnt);
        checks.waitTicks("armor_stand_tnt_explosion_5s", 100);
        checks.snapshot("armored_stands_after_tnt", area);
        operations.undo("armor_stand_tnt");
        checks.assertSnapshot("undo_armor_stand_tnt", area, standsWithTnt);

        BlockPos chickenPosition = actions.surfacePosition(
                origin.getX() + 24, origin.getZ() + 8);
        UUID chicken = actions.spawnChicken(chickenPosition);
        LumiWorldSnapshot liveChicken = checks.snapshot("live_chicken", area);
        actions.attackEntity("kill_chicken", chicken, Items.DIAMOND_SWORD);
        checks.waitUntil("chicken_killed", 20,
                () -> !actions.hasEntity(chicken));
        checks.waitTicks("dead_chicken_settle_1s", 20);
        checks.snapshot("dead_chicken", area);
        operations.undo("killed_chicken");
        checks.assertSnapshot("undo_killed_chicken", area, liveChicken);
    }

    private void restoreAndAssert(
            String name, CommitId commit, LumiWorldSnapshot expected)
            throws IOException {
        operations.restore(name, commit);
        checks.assertSnapshot("restore_" + name, area, expected);
    }

    private List<BlockPos> randomSurfacePositions(
            long seed, int count, Set<BlockPos> excluded) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int z = -20; z <= 20; z++) {
            for (int x = -20; x <= 20; x++) {
                candidates.add(origin.offset(x, 0, z));
            }
        }
        Collections.shuffle(candidates, new Random(seed));
        Set<BlockPos> used = new HashSet<>(excluded);
        List<BlockPos> selected = new ArrayList<>(count);
        for (BlockPos candidate : candidates) {
            BlockPos surface = actions.surfacePosition(
                    candidate.getX(), candidate.getZ());
            if (used.add(surface)) {
                selected.add(surface);
                if (selected.size() == count) {
                    return List.copyOf(selected);
                }
            }
        }
        throw new AssertionError("Could not plan " + count + " random blocks");
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record WorldOrigin(BlockPos position, int minY, int maxY) { }
}
