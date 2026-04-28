package io.github.luma.baselinegametest;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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

final class BaselineGameplaySuite {

    private final List<BaselineScenario> scenarios = List.of(
            new BlockMutationScenario(),
            new BulkBlockScenario(),
            new BlockEntityScenario(),
            new RedstoneScenario(),
            new FluidScenario(),
            new DoorScenario(),
            new OrientationScenario(),
            new CropScenario(),
            new OpenableScenario(),
            new ItemEntityScenario(),
            new EntityLifecycleScenario()
    );

    void run(MinecraftServer server, BaselineChecks checks) {
        ServerLevel level = server.overworld();
        ServerPlayer player = this.firstPlayer(server);
        BlockPos origin = player.blockPosition().offset(4, 8, 4);
        BaselineWorldSlice slice = new BaselineWorldSlice(level, origin, this.scenarios.size());
        slice.clear();
        for (int index = 0; index < this.scenarios.size(); index++) {
            BaselineScenario scenario = this.scenarios.get(index);
            scenario.run(new BaselineScenarioContext(level, player, origin.offset(0, index * 6, 0)), checks);
        }
        slice.clear();
    }

    private ServerPlayer firstPlayer(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            throw new IllegalStateException("No baseline test player is available");
        }
        return players.getFirst();
    }

    private interface BaselineScenario {
        void run(BaselineScenarioContext context, BaselineChecks checks);
    }

    private record BaselineScenarioContext(ServerLevel level, ServerPlayer player, BlockPos origin) {
    }

    private record BaselineWorldSlice(ServerLevel level, BlockPos origin, int scenarioCount) {

        void clear() {
            BlockPos max = this.origin.offset(8, (this.scenarioCount * 6) + 6, 8);
            for (BlockPos pos : BlockPos.betweenClosed(this.origin, max)) {
                this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static final class BlockMutationScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos support = context.origin().offset(1, 1, 1);
            BlockPos flower = support.above();

            context.level().setBlock(support, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            context.level().setBlock(flower, Blocks.DANDELION.defaultBlockState(), 3);

            boolean destroyed = context.player().gameMode.destroyBlock(support);
            checks.check(destroyed, "baseline block mutation destroys support");
            checks.check(context.level().getBlockState(support).isAir(), "baseline support block became air");
            checks.check(context.level().getBlockState(flower).isAir(), "baseline adjacent flower became air");
        }
    }

    private static final class BulkBlockScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            int placed = 0;
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 6; z++) {
                        context.level().setBlock(context.origin().offset(x, y, z), Blocks.COPPER_BLOCK.defaultBlockState(), 3);
                        placed++;
                    }
                }
            }

            int verified = 0;
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 6; z++) {
                        if (context.level().getBlockState(context.origin().offset(x, y, z)).is(Blocks.COPPER_BLOCK)) {
                            verified++;
                        }
                    }
                }
            }
            checks.check(verified == placed, "baseline bulk block placement verified " + verified + "/" + placed);
        }
    }

    private static final class BlockEntityScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos barrel = context.origin().offset(1, 1, 1);
            context.level().setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
            checks.check(context.level().getBlockEntity(barrel) instanceof BarrelBlockEntity, "baseline created barrel block entity");
            if (context.level().getBlockEntity(barrel) instanceof BarrelBlockEntity blockEntity) {
                blockEntity.setItem(0, new ItemStack(Items.DIAMOND, 16));
                checks.check(blockEntity.getItem(0).getCount() == 16, "baseline block entity inventory updated");
            }
        }
    }

    private static final class RedstoneScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos lamp = context.origin().offset(1, 1, 1);
            context.level().setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
            context.level().setBlock(lamp.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            checks.check(context.level().getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "baseline redstone lamp lit");
        }
    }

    private static final class FluidScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos water = context.origin().offset(1, 1, 1);
            context.level().setBlock(water, Blocks.WATER.defaultBlockState(), 3);
            checks.check(context.level().getFluidState(water).isSource(), "baseline placed water source");
        }
    }

    private static final class EntityLifecycleScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            Entity entity = EntityType.ARMOR_STAND.create(context.level(), EntitySpawnReason.COMMAND);
            checks.check(entity != null, "baseline created an interaction entity");
            if (entity == null) {
                return;
            }

            BlockPos marker = context.origin().offset(1, 1, 1);
            entity.snapTo(marker.getX() + 0.5D, marker.getY(), marker.getZ() + 0.5D, 0.0F, 0.0F);
            entity.setCustomName(Component.literal("baseline-entity"));
            entity.setGlowingTag(true);
            context.level().addFreshEntity(entity);
            checks.check(!entity.isRemoved(), "baseline spawned an entity");
            checks.check(entity.hasCustomName(), "baseline updated entity custom name");
            checks.check(entity.isCurrentlyGlowing(), "baseline updated entity glowing state");
            entity.discard();
            checks.check(entity.isRemoved(), "baseline removed an entity");
        }
    }

    private static final class DoorScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos lower = context.origin().offset(1, 1, 1);
            BlockPos upper = lower.above();
            context.level().setBlock(lower, Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
            context.level().setBlock(upper, Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
            checks.check(context.level().getBlockState(lower).is(Blocks.OAK_DOOR), "baseline placed lower door block");
            checks.check(context.level().getBlockState(upper).is(Blocks.OAK_DOOR), "baseline placed upper door block");
        }
    }

    private static final class OrientationScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos stairs = context.origin().offset(1, 1, 1);
            context.level().setBlock(stairs, Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.EAST), 3);
            checks.check(context.level().getBlockState(stairs).getValue(StairBlock.FACING) == Direction.EAST,
                    "baseline preserved oriented stair state");
        }
    }

    private static final class CropScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos farmland = context.origin().offset(1, 1, 1);
            BlockPos crop = farmland.above();
            context.level().setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 3);
            context.level().setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 3);
            checks.check(context.level().getBlockState(farmland).is(Blocks.FARMLAND), "baseline placed farmland");
            checks.check(context.level().getBlockState(crop).getValue(CropBlock.AGE) == 7,
                    "baseline preserved mature crop state");
        }
    }

    private static final class OpenableScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos trapdoor = context.origin().offset(1, 1, 1);
            context.level().setBlock(trapdoor, Blocks.OAK_TRAPDOOR.defaultBlockState()
                    .setValue(TrapDoorBlock.OPEN, true), 3);
            checks.check(context.level().getBlockState(trapdoor).getValue(TrapDoorBlock.OPEN),
                    "baseline preserved open trapdoor state");
        }
    }

    private static final class ItemEntityScenario implements BaselineScenario {

        @Override
        public void run(BaselineScenarioContext context, BaselineChecks checks) {
            BlockPos marker = context.origin().offset(1, 1, 1);
            ItemEntity item = new ItemEntity(
                    context.level(),
                    marker.getX() + 0.5D,
                    marker.getY() + 0.5D,
                    marker.getZ() + 0.5D,
                    new ItemStack(Items.OAK_PLANKS, 8)
            );
            context.level().addFreshEntity(item);
            checks.check(!item.isRemoved(), "baseline spawned item entity");
            item.discard();
            checks.check(item.isRemoved(), "baseline removed item entity");
        }
    }
}
