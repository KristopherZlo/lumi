package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Real integrated-world gameplay actions used by the Lumi runtime suite.
 */
final class SingleplayerGameplayRegressionSuite {

    private final List<GameplayScenario> scenarios = List.of(
            new AdjacentBlockBreakScenario(),
            new BulkPlacementScenario(),
            new BlockEntityScenario(),
            new RedstoneScenario(),
            new FluidScenario(),
            new DoorScenario(),
            new OrientationScenario(),
            new CropScenario(),
            new OpenableScenario(),
            new ItemEntityScenario(),
            new EntitySpawnScenario(),
            new WaterBridgeScenario()
    );

    GameplayRegressionReport run(
            ServerLevel level,
            ServerPlayer player,
            SingleplayerTestVolume volume,
            String actor
    ) {
        GameplayChecks checks = new GameplayChecks();
        GameplayScenarioContext context = new GameplayScenarioContext(level, player, volume, actor, checks);
        for (GameplayScenario scenario : this.scenarios) {
            long startedAt = System.nanoTime();
            scenario.run(context);
            context.recordTiming(this.scenarioName(scenario), System.nanoTime() - startedAt);
        }
        return context.report();
    }

    private String scenarioName(GameplayScenario scenario) {
        String name = scenario.getClass().getSimpleName();
        return name.endsWith("Scenario") ? name.substring(0, name.length() - "Scenario".length()) : name;
    }

    private interface GameplayScenario {
        void run(GameplayScenarioContext context);
    }

    record GameplayRegressionReport(
            List<GameplayCheck> checks,
            Set<BlockPoint> expectedDraftBlocks,
            Set<BlockPoint> latestUndoRedoBlocks,
            int expectedEntityChanges,
            List<Entity> spawnedEntities,
            List<GameplayTiming> timings
    ) {

        void cleanup() {
            WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
                for (Entity entity : this.spawnedEntities) {
                    if (entity != null && !entity.isRemoved()) {
                        entity.discard();
                    }
                }
            });
        }
    }

    record GameplayCheck(String label, boolean passed) {
    }

    record GameplayTiming(String scenario, long durationNanos) {

        long durationMillis() {
            return Math.max(0L, this.durationNanos) / 1_000_000L;
        }
    }

    private static final class GameplayChecks {

        private final List<GameplayCheck> results = new ArrayList<>();

        void check(boolean condition, String label) {
            this.results.add(new GameplayCheck(label, condition));
        }

        List<GameplayCheck> results() {
            return List.copyOf(this.results);
        }
    }

    private static final class GameplayScenarioContext {

        private final ServerLevel level;
        private final ServerPlayer player;
        private final SingleplayerTestVolume volume;
        private final String actor;
        private final GameplayChecks checks;
        private final SingleplayerPlayerActionDriver playerActions;
        private final Set<BlockPoint> expectedDraftBlocks = new LinkedHashSet<>();
        private final Set<BlockPoint> latestUndoRedoBlocks = new LinkedHashSet<>();
        private final List<Entity> spawnedEntities = new ArrayList<>();
        private final List<GameplayTiming> timings = new ArrayList<>();
        private int expectedEntityChanges;

        private GameplayScenarioContext(
                ServerLevel level,
                ServerPlayer player,
                SingleplayerTestVolume volume,
                String actor,
                GameplayChecks checks
        ) {
            this.level = level;
            this.player = player;
            this.volume = volume;
            this.actor = actor;
            this.checks = checks;
            this.playerActions = new SingleplayerPlayerActionDriver(level, player);
        }

        private void trackedPlayerAction(Runnable runnable) {
            WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, this.actor, true);
            try {
                runnable.run();
            } finally {
                WorldMutationContext.popSource();
            }
        }

        private void expectDraftBlock(BlockPos pos) {
            this.expectedDraftBlocks.add(BlockPoint.from(pos));
        }

        private void expectLatestUndoRedoBlock(BlockPos pos) {
            this.latestUndoRedoBlocks.add(BlockPoint.from(pos));
            this.expectDraftBlock(pos);
        }

        private void expectEntityChange(Entity entity) {
            this.expectedEntityChanges += 1;
            this.trackSpawnedEntity(entity);
        }

        private void trackSpawnedEntity(Entity entity) {
            this.spawnedEntities.add(entity);
        }

        private void recordTiming(String scenario, long durationNanos) {
            this.timings.add(new GameplayTiming(scenario, durationNanos));
        }

        private GameplayRegressionReport report() {
            return new GameplayRegressionReport(
                    this.checks.results(),
                    Set.copyOf(this.expectedDraftBlocks),
                    Set.copyOf(this.latestUndoRedoBlocks),
                    this.expectedEntityChanges,
                    List.copyOf(this.spawnedEntities),
                    List.copyOf(this.timings)
            );
        }
    }

    private static final class AdjacentBlockBreakScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos support = context.volume.min().offset(0, 0, 0);
            BlockPos flower = support.above();
            WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
                context.level.setBlock(support, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                context.level.setBlock(flower, Blocks.DANDELION.defaultBlockState(), 3);
            });

            boolean destroyed = context.player.gameMode.destroyBlock(support);
            context.checks.check(destroyed, "gameplay block break destroys support");
            context.checks.check(context.level.getBlockState(support).isAir(), "gameplay support block became air");
            context.checks.check(context.level.getBlockState(flower).isAir(), "gameplay adjacent flower became air");
            context.expectDraftBlock(support);
            context.expectDraftBlock(flower);
        }
    }

    private static final class BulkPlacementScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            List<BlockPos> placedBlocks = new ArrayList<>();
            context.trackedPlayerAction(() -> {
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        BlockPos pos = context.volume.min().offset(x, SingleplayerTestVolume.HEIGHT - 1, z);
                        context.level.setBlock(pos, Blocks.COPPER_BLOCK.defaultBlockState(), 3);
                        placedBlocks.add(pos);
                    }
                }
            });

            long verified = placedBlocks.stream()
                    .filter(pos -> context.level.getBlockState(pos).is(Blocks.COPPER_BLOCK))
                    .count();
            context.checks.check(verified == placedBlocks.size(),
                    "gameplay bulk placement verified " + verified + "/" + placedBlocks.size());
            placedBlocks.forEach(context::expectDraftBlock);
        }
    }

    private static final class BlockEntityScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos barrel = context.volume.min().offset(3, 1, 0);
            context.trackedPlayerAction(() -> context.level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3));
            context.checks.check(context.level.getBlockEntity(barrel) instanceof BarrelBlockEntity,
                    "gameplay created barrel block entity");
            if (context.level.getBlockEntity(barrel) instanceof BarrelBlockEntity blockEntity) {
                blockEntity.setItem(0, new ItemStack(Items.DIAMOND, 16));
                context.checks.check(blockEntity.getItem(0).getCount() == 16,
                        "gameplay block entity inventory updated");
            }
            context.expectDraftBlock(barrel);
        }
    }

    private static final class RedstoneScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos lamp = context.volume.min().offset(1, 1, 3);
            BlockPos power = lamp.west();
            context.trackedPlayerAction(() -> {
                context.level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
                context.level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            });
            context.checks.check(context.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                    "gameplay redstone lamp lit");
            context.expectDraftBlock(lamp);
            context.expectDraftBlock(power);
        }
    }

    private static final class FluidScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos water = context.volume.min().offset(3, 1, 3);
            context.trackedPlayerAction(() -> context.level.setBlock(water, Blocks.WATER.defaultBlockState(), 3));
            context.checks.check(context.level.getFluidState(water).isSource(), "gameplay placed water source");
            context.expectDraftBlock(water);
        }
    }

    private static final class EntitySpawnScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            Entity entity = EntityType.ARMOR_STAND.create(context.level, EntitySpawnReason.COMMAND);
            context.checks.check(entity != null, "gameplay created a builder-relevant entity");
            if (entity == null) {
                return;
            }

            BlockPos marker = context.volume.min().offset(2, 1, 2);
            entity.snapTo(marker.getX() + 0.5D, marker.getY(), marker.getZ() + 0.5D, 0.0F, 0.0F);
            entity.setCustomName(Component.literal("lumi-runtime-entity"));
            entity.setGlowingTag(true);
            context.trackedPlayerAction(() -> context.level.addFreshEntity(entity));
            context.checks.check(!entity.isRemoved(), "gameplay spawned an entity");
            context.checks.check(entity.hasCustomName(), "gameplay updated entity custom name");
            context.checks.check(entity.isCurrentlyGlowing(), "gameplay updated entity glowing state");
            context.expectEntityChange(entity);
        }
    }

    private static final class DoorScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos lower = context.volume.min().offset(4, 0, 4);
            BlockPos upper = lower.above();
            context.trackedPlayerAction(() -> {
                context.level.setBlock(lower, Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
                context.level.setBlock(upper, Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
            });
            context.checks.check(context.level.getBlockState(lower).is(Blocks.OAK_DOOR),
                    "gameplay placed lower door block");
            context.checks.check(context.level.getBlockState(upper).is(Blocks.OAK_DOOR),
                    "gameplay placed upper door block");
            context.expectDraftBlock(lower);
            context.expectDraftBlock(upper);
        }
    }

    private static final class OrientationScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos stairs = context.volume.min().offset(4, 2, 1);
            context.trackedPlayerAction(() -> context.level.setBlock(stairs, Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.EAST), 3));
            context.checks.check(context.level.getBlockState(stairs).getValue(StairBlock.FACING) == Direction.EAST,
                    "gameplay preserved oriented stair state");
            context.expectDraftBlock(stairs);
        }
    }

    private static final class CropScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos farmland = context.volume.min().offset(2, 0, 4);
            BlockPos crop = farmland.above();
            context.trackedPlayerAction(() -> {
                context.level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 3);
                context.level.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 3);
            });
            context.checks.check(context.level.getBlockState(farmland).is(Blocks.FARMLAND),
                    "gameplay placed farmland");
            context.checks.check(context.level.getBlockState(crop).getValue(CropBlock.AGE) == 7,
                    "gameplay preserved mature crop state");
            context.expectDraftBlock(farmland);
            context.expectDraftBlock(crop);
        }
    }

    private static final class OpenableScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos trapdoor = context.volume.min().offset(4, 2, 4);
            context.trackedPlayerAction(() -> context.level.setBlock(trapdoor, Blocks.OAK_TRAPDOOR.defaultBlockState()
                    .setValue(TrapDoorBlock.OPEN, true), 3));
            context.checks.check(context.level.getBlockState(trapdoor).getValue(TrapDoorBlock.OPEN),
                    "gameplay preserved open trapdoor state");
            context.expectDraftBlock(trapdoor);
        }
    }

    private static final class ItemEntityScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos marker = context.volume.min().offset(1, 2, 1);
            ItemEntity item = new ItemEntity(
                    context.level,
                    marker.getX() + 0.5D,
                    marker.getY() + 0.5D,
                    marker.getZ() + 0.5D,
                    new ItemStack(Items.OAK_PLANKS, 8)
            );
            context.trackedPlayerAction(() -> context.level.addFreshEntity(item));
            context.checks.check(!item.isRemoved(), "gameplay spawned item entity");
            item.discard();
            context.checks.check(item.isRemoved(), "gameplay removed item entity");
            context.trackSpawnedEntity(item);
        }
    }

    private static final class WaterBridgeScenario implements GameplayScenario {

        private static final int BRIDGE_LENGTH = 6;

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos anchor = context.volume.min().offset(1, 2, 8);
            List<BlockPos> bridge = new ArrayList<>();
            WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
                context.level.setBlock(anchor, Blocks.STONE.defaultBlockState(), 3);
                for (int index = 1; index <= BRIDGE_LENGTH; index++) {
                    BlockPos water = anchor.offset(index, -1, 0);
                    context.level.setBlock(water.below(), Blocks.STONE.defaultBlockState(), 3);
                    context.level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
                }
            });

            BlockPos clicked = anchor;
            boolean placedAll = true;
            for (int index = 1; index <= BRIDGE_LENGTH; index++) {
                BlockPos expected = anchor.offset(index, 0, 0);
                placedAll = context.playerActions.placeAgainst(clicked, Direction.EAST, Blocks.SPRUCE_PLANKS, expected)
                        && placedAll;
                bridge.add(expected);
                clicked = expected;
            }

            context.checks.check(placedAll, "gameplay player placed a bridge over water through gameMode useItemOn");
            long verified = bridge.stream()
                    .filter(pos -> context.level.getBlockState(pos).is(Blocks.SPRUCE_PLANKS))
                    .filter(pos -> context.level.getFluidState(pos.below()).isSource())
                    .count();
            context.checks.check(verified == BRIDGE_LENGTH,
                    "gameplay water bridge verified " + verified + "/" + BRIDGE_LENGTH + " planks above source water");
            bridge.forEach(context::expectDraftBlock);
            context.expectLatestUndoRedoBlock(bridge.getLast());
        }
    }
}
