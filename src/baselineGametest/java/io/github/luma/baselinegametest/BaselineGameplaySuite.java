package io.github.luma.baselinegametest;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

final class BaselineGameplaySuite {

    private final List<BaselineScenario> scenarios = List.of(
            new BlockMutationScenario(),
            new BulkBlockScenario(),
            new BlockEntityScenario(),
            new RedstoneScenario(),
            new FluidScenario(),
            new EntityLifecycleScenario()
    );

    void run(MinecraftServer server, BaselineChecks checks) {
        ServerLevel level = server.overworld();
        ServerPlayer player = this.firstPlayer(server);
        BlockPos origin = player.blockPosition().offset(4, 8, 4);
        BaselineWorldSlice slice = new BaselineWorldSlice(level, origin);
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

    private record BaselineWorldSlice(ServerLevel level, BlockPos origin) {

        void clear() {
            for (BlockPos pos : BlockPos.betweenClosed(this.origin, this.origin.offset(8, 36, 8))) {
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
}
