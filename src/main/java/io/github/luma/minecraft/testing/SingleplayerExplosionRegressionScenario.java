package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Controlled TNT regression driven through normal player interaction APIs.
 */
final class SingleplayerExplosionRegressionScenario {

    ExplosionRegressionReport start(ServerLevel level, ServerPlayer player, SingleplayerTestVolume volume) {
        SingleplayerPlayerActionDriver actions = new SingleplayerPlayerActionDriver(level, player);
        BlockPos support = volume.min().offset(8, 0, 2);
        BlockPos tnt = support.above();
        Set<BlockPos> witnesses = Set.of(
                tnt.north(),
                tnt.south(),
                tnt.east(),
                tnt.west()
        );

        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            for (BlockPos witness : witnesses) {
                level.setBlock(witness, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        });

        boolean placed = actions.placeAgainst(support, Direction.UP, Blocks.TNT, tnt);
        boolean ignited = actions.useItemOn(tnt, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL, 1));
        return new ExplosionRegressionReport(placed, ignited, tnt, Set.copyOf(witnesses));
    }

    record ExplosionRegressionReport(
            boolean placed,
            boolean ignited,
            BlockPos tntPos,
            Set<BlockPos> witnessBlocks
    ) {

        Set<BlockPoint> expectedUndoRedoBlocks() {
            LinkedHashSet<BlockPoint> blocks = new LinkedHashSet<>();
            blocks.add(BlockPoint.from(this.tntPos));
            for (BlockPos witness : this.witnessBlocks) {
                blocks.add(BlockPoint.from(witness));
            }
            return Set.copyOf(blocks);
        }

        boolean exploded(ServerLevel level) {
            return level.getBlockState(this.tntPos).isAir()
                    && this.witnessBlocks.stream().anyMatch(pos -> level.getBlockState(pos).isAir());
        }

        boolean restoredAfterUndo(ServerLevel level) {
            return level.getBlockState(this.tntPos).is(Blocks.TNT)
                    && this.witnessBlocks.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.OAK_PLANKS));
        }

        boolean removedAfterRedo(ServerLevel level) {
            return level.getBlockState(this.tntPos).isAir()
                    && this.witnessBlocks.stream().anyMatch(pos -> level.getBlockState(pos).isAir());
        }
    }
}
