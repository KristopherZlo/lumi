package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Real integrated-world gameplay actions used by the Lumi runtime suite.
 */
final class SingleplayerGameplayRegressionSuite {

    private final List<GameplayScenario> scenarios = List.of(
            new AdjacentBlockBreakScenario(),
            new BulkPlacementScenario(),
            new BlockEntityScenario(),
            new RedstoneScenario(),
            new ClosedMechanismScenario(),
            new FluidScenario(),
            new DoorScenario(),
            new OrientationScenario(),
            new CropScenario(),
            new OpenableScenario(),
            new ItemEntityScenario(),
            new EntitySpawnScenario(),
            new WaterBridgeScenario(),
            new MechanismAndWaterUndoRedoScenario(),
            new PreCutWaterReleaseUndoRedoScenario()
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
            Set<BlockPoint> unexpectedDraftBlocks,
            Map<BlockPoint, Map<String, String>> expectedDraftProperties,
            Set<BlockPoint> latestUndoRedoBlocks,
            List<ReplayBlockExpectation> latestReplayBlocks,
            List<UndoOnlyBlockExpectation> latestUndoOnlyBlocks,
            int expectedEntityChanges,
            Set<String> expectedEntityIds,
            Map<String, BlockPoint> expectedEntityPositions,
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

    record ReplayBlockExpectation(BlockPoint pos, Block undoBlock, Block redoBlock) {
    }

    record UndoOnlyBlockExpectation(BlockPoint pos, Block undoBlock) {
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
        private final Set<BlockPoint> unexpectedDraftBlocks = new LinkedHashSet<>();
        private final Map<BlockPoint, Map<String, String>> expectedDraftProperties = new LinkedHashMap<>();
        private final Set<BlockPoint> latestUndoRedoBlocks = new LinkedHashSet<>();
        private final List<ReplayBlockExpectation> latestReplayBlocks = new ArrayList<>();
        private final List<UndoOnlyBlockExpectation> latestUndoOnlyBlocks = new ArrayList<>();
        private final Set<String> expectedEntityIds = new LinkedHashSet<>();
        private final Map<String, BlockPoint> expectedEntityPositions = new LinkedHashMap<>();
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

        private boolean destroyTransientFixtureBlock(BlockPos pos) {
            try (WorldMutationContext.SuppressionFrame ignored = WorldMutationContext.pushCaptureSuppression()) {
                return this.playerActions.destroyBlock(pos);
            }
        }

        private void expectDraftBlock(BlockPos pos) {
            this.expectedDraftBlocks.add(BlockPoint.from(pos));
        }

        private void expectNoDraftBlock(BlockPos pos) {
            this.unexpectedDraftBlocks.add(BlockPoint.from(pos));
        }

        private void expectDraftProperty(BlockPos pos, String propertyName, String propertyValue) {
            this.expectDraftBlock(pos);
            this.expectedDraftProperties
                    .computeIfAbsent(BlockPoint.from(pos), ignored -> new LinkedHashMap<>())
                    .put(propertyName, propertyValue);
        }

        private void expectLatestUndoRedoBlock(BlockPos pos) {
            this.expectLatestReplayBlock(pos, Blocks.AIR, this.level.getBlockState(pos).getBlock());
        }

        private void beginLatestUndoRedoAction() {
            this.latestUndoRedoBlocks.clear();
            this.latestReplayBlocks.clear();
            this.latestUndoOnlyBlocks.clear();
        }

        private void expectLatestReplayBlock(BlockPos pos, Block undoBlock, Block redoBlock) {
            this.expectLatestReplayBlock(pos, undoBlock, redoBlock, true);
        }

        private void expectLatestReplayBlock(BlockPos pos, Block undoBlock, Block redoBlock, boolean expectDraft) {
            BlockPoint point = BlockPoint.from(pos);
            this.latestUndoRedoBlocks.add(point);
            this.latestReplayBlocks.add(new ReplayBlockExpectation(point, undoBlock, redoBlock));
            if (expectDraft) {
                this.expectDraftBlock(pos);
            }
        }

        private void expectLatestUndoOnlyBlock(BlockPos pos, Block undoBlock) {
            this.latestUndoOnlyBlocks.add(new UndoOnlyBlockExpectation(BlockPoint.from(pos), undoBlock));
        }

        private void expectEntityChange(Entity entity) {
            this.expectedEntityChanges += 1;
            if (entity != null) {
                this.expectedEntityIds.add(entity.getStringUUID());
                this.expectedEntityPositions.put(entity.getStringUUID(), BlockPoint.from(entity.blockPosition()));
            }
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
                    Set.copyOf(this.unexpectedDraftBlocks),
                    this.expectedDraftProperties(),
                    Set.copyOf(this.latestUndoRedoBlocks),
                    List.copyOf(this.latestReplayBlocks),
                    List.copyOf(this.latestUndoOnlyBlocks),
                    this.expectedEntityChanges,
                    Set.copyOf(this.expectedEntityIds),
                    Map.copyOf(this.expectedEntityPositions),
                    List.copyOf(this.spawnedEntities),
                    List.copyOf(this.timings)
            );
        }

        private Map<BlockPoint, Map<String, String>> expectedDraftProperties() {
            LinkedHashMap<BlockPoint, Map<String, String>> copy = new LinkedHashMap<>();
            for (Map.Entry<BlockPoint, Map<String, String>> entry : this.expectedDraftProperties.entrySet()) {
                copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
        }
    }

    private static final class AdjacentBlockBreakScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos support = context.volume.min().offset(0, 1, 0);
            BlockPos flower = support.above();
            WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
                context.level.setBlock(support, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                context.level.setBlock(flower, Blocks.DANDELION.defaultBlockState(), 3);
            });

            boolean destroyed = context.destroyTransientFixtureBlock(support);
            context.checks.check(destroyed, "gameplay block break destroys support");
            context.checks.check(context.level.getBlockState(support).isAir(), "gameplay support block became air");
            context.checks.check(context.level.getBlockState(flower).isAir(), "gameplay adjacent flower became air");
            context.expectNoDraftBlock(support);
            context.expectNoDraftBlock(flower);
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
            context.expectDraftProperty(lamp, "lit", "true");
            context.expectDraftBlock(power);

            BlockPos leverSupport = context.volume.min().offset(10, 1, 3);
            BlockPos lever = leverSupport.above();
            context.trackedPlayerAction(() -> {
                context.level.setBlock(leverSupport, Blocks.STONE.defaultBlockState(), 3);
                context.level.setBlock(lever, Blocks.LEVER.defaultBlockState()
                        .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                        .setValue(LeverBlock.FACING, Direction.NORTH)
                        .setValue(LeverBlock.POWERED, false), 3);
            });
            boolean usedLever = context.playerActions.useBlock(lever, Direction.UP);
            context.checks.check(usedLever, "gameplay player toggled lever");
            context.checks.check(context.level.getBlockState(lever).getValue(LeverBlock.POWERED),
                    "gameplay lever stored powered state");
            context.expectDraftBlock(leverSupport);
            context.expectDraftProperty(lever, "powered", "true");

            BlockPos piston = context.volume.min().offset(6, 1, 3);
            BlockPos head = piston.east();
            BlockPos movedTo = head.east();
            context.trackedPlayerAction(() -> {
                context.level.setBlock(piston, Blocks.PISTON.defaultBlockState()
                        .setValue(PistonBaseBlock.FACING, Direction.EAST), 3);
                context.level.setBlock(head, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                WorldMutationContext.runWithSource(WorldMutationSource.PISTON, () -> {
                    context.level.setBlock(piston, Blocks.PISTON.defaultBlockState()
                            .setValue(PistonBaseBlock.FACING, Direction.EAST)
                            .setValue(PistonBaseBlock.EXTENDED, true), 3);
                    context.level.setBlock(head, Blocks.PISTON_HEAD.defaultBlockState()
                            .setValue(PistonHeadBlock.FACING, Direction.EAST)
                            .setValue(PistonHeadBlock.TYPE, PistonType.DEFAULT), 3);
                    context.level.setBlock(movedTo, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                });
            });
            context.checks.check(context.level.getBlockState(piston).getValue(PistonBaseBlock.EXTENDED),
                    "gameplay piston fallout kept extended base");
            context.checks.check(context.level.getBlockState(head).is(Blocks.PISTON_HEAD),
                    "gameplay piston fallout kept settled head");
            context.checks.check(context.level.getBlockState(movedTo).is(Blocks.OAK_PLANKS),
                    "gameplay piston fallout kept the final moved block");
            context.expectDraftProperty(piston, "extended", "true");
            context.expectDraftBlock(head);
            context.expectDraftBlock(movedTo);
        }
    }

    private static final class ClosedMechanismScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos loopBase = context.volume.min().offset(6, 1, 6);
            List<BlockPos> supports = List.of(
                    loopBase,
                    loopBase.east(),
                    loopBase.east().south(),
                    loopBase.south()
            );
            List<BlockPos> wires = supports.stream()
                    .map(BlockPos::above)
                    .toList();
            BlockPos power = wires.getFirst().west();
            BlockPos lamp = supports.get(2);

            context.trackedPlayerAction(() -> {
                supports.forEach(pos -> context.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3));
                context.level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
                wires.forEach(pos -> context.level.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), 3));
                context.level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            });

            long liveWires = wires.stream()
                    .filter(pos -> context.level.getBlockState(pos).is(Blocks.REDSTONE_WIRE))
                    .count();
            context.checks.check(liveWires == wires.size(),
                    "gameplay closed mechanism kept its redstone loop");
            context.checks.check(context.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                    "gameplay closed mechanism powered its lamp");

            supports.forEach(context::expectDraftBlock);
            wires.forEach(context::expectDraftBlock);
            context.expectDraftProperty(
                    wires.getFirst(),
                    "power",
                    String.valueOf(context.level.getBlockState(wires.getFirst()).getValue(RedStoneWireBlock.POWER))
            );
            context.expectDraftBlock(power);
            context.expectDraftBlock(lamp);
            context.expectDraftProperty(lamp, "lit", "true");
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
            Entity entity = EntityType.COW.create(context.level, EntitySpawnReason.COMMAND);
            context.checks.check(entity != null, "gameplay created a non-player entity");
            if (entity == null) {
                return;
            }

            BlockPos marker = context.volume.min().offset(2, 1, 2);
            entity.snapTo(marker.getX() + 0.5D, marker.getY(), marker.getZ() + 0.5D, 0.0F, 0.0F);
            context.trackedPlayerAction(() -> {
                context.level.addFreshEntity(entity);
                entity.snapTo(marker.getX() + 1.5D, marker.getY(), marker.getZ() + 0.5D, 90.0F, 0.0F);
                entity.setCustomName(Component.literal("lumi-runtime-entity"));
                entity.setGlowingTag(true);
            });
            context.checks.check(!entity.isRemoved(), "gameplay spawned an entity");
            context.checks.check(entity.blockPosition().equals(marker.east()), "gameplay updated entity position");
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
            BlockPos farmland = context.volume.min().offset(2, 1, 4);
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
            context.expectEntityChange(item);
        }
    }

    private static final class WaterBridgeScenario implements GameplayScenario {

        private static final int BRIDGE_LENGTH = 6;

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos anchor = context.volume.min().offset(1, 2, 8);
            List<BlockPos> bridge = new ArrayList<>();
            context.beginLatestUndoRedoAction();
            Set<BlockPos> fixtureBlocks = new LinkedHashSet<>();
            context.trackedPlayerAction(() -> {
                context.level.setBlock(anchor, Blocks.STONE.defaultBlockState(), 3);
                fixtureBlocks.add(anchor);
                for (int index = 1; index <= BRIDGE_LENGTH; index++) {
                    BlockPos water = anchor.offset(index, -1, 0);
                    context.level.setBlock(water.below(), Blocks.STONE.defaultBlockState(), 3);
                    context.level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
                    fixtureBlocks.add(water.below());
                    fixtureBlocks.add(water);
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
            fixtureBlocks.forEach(context::expectDraftBlock);
            bridge.forEach(context::expectDraftBlock);
            context.expectLatestUndoRedoBlock(bridge.getLast());
        }
    }

    private static final class MechanismAndWaterUndoRedoScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos piston = context.volume.min().offset(7, 1, 10);
            BlockPos home = piston.east();
            BlockPos moved = home.east();
            BlockPos water = piston.west();
            BlockPos power = piston.below();
            BlockPos torch = moved.east();
            BlockPos torchSupport = torch.below();

            this.loadFixture(context, piston, home, water, power, torchSupport, torch);
            context.beginLatestUndoRedoAction();
            context.trackedPlayerAction(() -> {
                context.level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
                context.level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                WorldMutationContext.runWithSource(WorldMutationSource.PISTON, () -> {
                    context.level.setBlock(piston, Blocks.STICKY_PISTON.defaultBlockState()
                            .setValue(PistonBaseBlock.FACING, Direction.EAST)
                            .setValue(PistonBaseBlock.EXTENDED, true), 3);
                    context.level.setBlock(home, Blocks.PISTON_HEAD.defaultBlockState()
                            .setValue(PistonHeadBlock.FACING, Direction.EAST)
                            .setValue(PistonHeadBlock.TYPE, PistonType.STICKY), 3);
                    context.level.setBlock(moved, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                });
                WorldMutationContext.runWithSource(WorldMutationSource.FLUID, () ->
                        context.level.setBlock(torch, Blocks.AIR.defaultBlockState(), 3));
            });

            context.checks.check(context.level.getBlockState(piston).getValue(PistonBaseBlock.EXTENDED),
                    "gameplay latest sticky piston extended");
            context.checks.check(context.level.getBlockState(home).is(Blocks.PISTON_HEAD),
                    "gameplay latest sticky piston kept settled head");
            context.checks.check(context.level.getBlockState(moved).is(Blocks.OAK_PLANKS),
                    "gameplay latest sticky piston moved block");
            context.checks.check(context.level.getBlockState(torch).isAir(),
                    "gameplay latest water fallout broke redstone torch");
            context.expectLatestReplayBlock(water, Blocks.AIR, Blocks.WATER);
            context.expectLatestReplayBlock(power, Blocks.AIR, Blocks.REDSTONE_BLOCK);
            context.expectLatestReplayBlock(piston, Blocks.STICKY_PISTON, Blocks.STICKY_PISTON);
            context.expectLatestReplayBlock(home, Blocks.OAK_PLANKS, Blocks.PISTON_HEAD);
            context.expectLatestReplayBlock(moved, Blocks.AIR, Blocks.OAK_PLANKS);
            context.expectLatestReplayBlock(torch, Blocks.REDSTONE_TORCH, Blocks.AIR, false);
            context.expectDraftProperty(piston, "extended", "true");
        }

        private void loadFixture(
                GameplayScenarioContext context,
                BlockPos piston,
                BlockPos home,
                BlockPos water,
                BlockPos power,
                BlockPos torchSupport,
                BlockPos torch
        ) {
            List<BlockPos> waterContainment = List.of(water.below(), water.west(), water.north(), water.south());
            context.trackedPlayerAction(() -> {
                for (BlockPos containment : waterContainment) {
                    context.level.setBlock(containment, Blocks.STONE.defaultBlockState(), 3);
                }
                context.level.setBlock(piston, Blocks.STICKY_PISTON.defaultBlockState()
                        .setValue(PistonBaseBlock.FACING, Direction.EAST)
                        .setValue(PistonBaseBlock.EXTENDED, false), 3);
                context.level.setBlock(home, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                context.level.setBlock(power, Blocks.AIR.defaultBlockState(), 3);
                context.level.setBlock(torchSupport, Blocks.STONE.defaultBlockState(), 3);
                context.level.setBlock(torch, Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
            });
            context.expectDraftBlock(piston);
            context.expectDraftBlock(home);
            context.expectDraftBlock(torchSupport);
            waterContainment.forEach(context::expectDraftBlock);
        }
    }

    private static final class PreCutWaterReleaseUndoRedoScenario implements GameplayScenario {

        @Override
        public void run(GameplayScenarioContext context) {
            BlockPos source = context.volume.min().offset(10, 2, 12);
            BlockPos gateway = source.east();
            List<BlockPos> preCutGaps = List.of(source.east(2), source.east(3), source.east(4));
            BlockPos liveOnlyTail = source.east(5);
            List<BlockPos> channel = new ArrayList<>();
            channel.add(source);
            channel.add(gateway);
            channel.addAll(preCutGaps);
            channel.add(liveOnlyTail);

            BlockPos sequentialSource = context.volume.min().offset(10, 4, 7);
            List<BlockPos> sequentialDigs = List.of(
                    sequentialSource.east(),
                    sequentialSource.east(2),
                    sequentialSource.east(3)
            );
            List<BlockPos> sequentialChannel = new ArrayList<>();
            sequentialChannel.add(sequentialSource);
            sequentialChannel.addAll(sequentialDigs);

            this.loadFixture(context, channel, Set.of(liveOnlyTail, liveOnlyTail.east()));
            this.loadFixture(context, sequentialChannel);
            for (BlockPos gap : preCutGaps) {
                context.trackedPlayerAction(() ->
                        context.level.setBlock(gap, Blocks.AIR.defaultBlockState(), 3));
                context.checks.check(context.level.getBlockState(gap).isAir(),
                        "gameplay pre-cut water gap stayed dry at " + gap.toShortString());
                context.expectDraftBlock(gap);
            }
            try (WorldMutationContext.SuppressionFrame ignored = WorldMutationContext.pushCaptureSuppression()) {
                context.level.setBlock(liveOnlyTail, Blocks.AIR.defaultBlockState(), 3);
            }
            context.checks.check(context.level.getBlockState(liveOnlyTail).isAir(),
                    "gameplay live-only water tail gap stayed dry before latest release");
            for (BlockPos dig : sequentialDigs.subList(0, sequentialDigs.size() - 1)) {
                context.trackedPlayerAction(() -> {
                    context.level.setBlock(dig, Blocks.AIR.defaultBlockState(), 3);
                    WorldMutationContext.runWithSource(WorldMutationSource.FLUID, () ->
                            context.level.setBlock(dig, flowingWater(), 3));
                });
                context.checks.check(context.level.getBlockState(dig).is(Blocks.WATER),
                        "gameplay sequential water dig filled older cell at " + dig.toShortString());
                context.expectDraftBlock(dig);
            }

            context.beginLatestUndoRedoAction();
            context.trackedPlayerAction(() -> {
                context.level.setBlock(gateway, Blocks.AIR.defaultBlockState(), 3);
                context.level.setBlock(sequentialDigs.getLast(), Blocks.AIR.defaultBlockState(), 3);
                WorldMutationContext.runWithSource(WorldMutationSource.FLUID, () -> {
                    context.level.setBlock(gateway, Blocks.WATER.defaultBlockState(), 3);
                    for (BlockPos gap : preCutGaps) {
                        context.level.setBlock(gap, Blocks.WATER.defaultBlockState(), 3);
                    }
                    context.level.setBlock(sequentialDigs.getLast(), flowingWater(), 3);
                });
            });
            try (WorldMutationContext.SuppressionFrame ignored = WorldMutationContext.pushCaptureSuppression()) {
                WorldMutationContext.runWithSource(WorldMutationSource.FLUID, () ->
                        context.level.setBlock(liveOnlyTail, flowingWater(), 3));
            }

            context.checks.check(context.level.getFluidState(gateway).isSource(),
                    "gameplay released generated water through latest gateway");
            long floodedPreCuts = preCutGaps.stream()
                    .filter(pos -> context.level.getBlockState(pos).is(Blocks.WATER))
                    .count();
            context.checks.check(floodedPreCuts == preCutGaps.size(),
                    "gameplay released water flooded only existing pre-cut gaps");
            context.checks.check(context.level.getBlockState(sequentialDigs.getLast()).is(Blocks.WATER),
                    "gameplay latest sequential water dig filled final cell");
            context.checks.check(context.level.getBlockState(liveOnlyTail).is(Blocks.WATER),
                    "gameplay live-only water tail flowed beyond captured latest payload");
            context.expectLatestReplayBlock(gateway, Blocks.GRASS_BLOCK, Blocks.WATER);
            preCutGaps.forEach(gap -> context.expectLatestReplayBlock(gap, Blocks.AIR, Blocks.WATER));
            context.expectLatestUndoOnlyBlock(liveOnlyTail, Blocks.AIR);
            sequentialDigs.subList(0, sequentialDigs.size() - 1)
                    .forEach(dig -> context.expectLatestReplayBlock(dig, Blocks.WATER, Blocks.WATER, false));
            context.expectLatestReplayBlock(sequentialDigs.getLast(), Blocks.GRASS_BLOCK, Blocks.WATER);
        }

        private void loadFixture(GameplayScenarioContext context, List<BlockPos> channel) {
            this.loadFixture(context, channel, Set.of());
        }

        private void loadFixture(
                GameplayScenarioContext context,
                List<BlockPos> channel,
                Set<BlockPos> excludedDraftBlocks
        ) {
            Set<BlockPos> containment = new LinkedHashSet<>();
            context.trackedPlayerAction(() -> {
                for (BlockPos pos : channel) {
                    containment.add(pos.below());
                    containment.add(pos.north());
                    containment.add(pos.south());
                    context.level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                }
                containment.add(channel.getFirst().west());
                containment.add(channel.getLast().east());
                for (BlockPos pos : containment) {
                    context.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                }
                context.level.setBlock(channel.getFirst(), Blocks.WATER.defaultBlockState(), 3);
            });
            channel.stream()
                    .filter(pos -> !excludedDraftBlocks.contains(pos))
                    .forEach(context::expectDraftBlock);
            containment.stream()
                    .filter(pos -> !excludedDraftBlocks.contains(pos))
                    .forEach(context::expectDraftBlock);
        }

        private BlockState flowingWater() {
            return Fluids.FLOWING_WATER.defaultFluidState()
                    .setValue(FlowingFluid.LEVEL, 7)
                    .createLegacyBlock();
        }
    }
}
