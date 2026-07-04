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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Controlled TNT regression driven through normal player interaction APIs.
 */
final class SingleplayerExplosionRegressionScenario {

    ExplosionRegressionReport start(ServerLevel level, ServerPlayer player, SingleplayerTestVolume volume, String actor) {
        return this.start(level, player, volume, actor, volume.min().offset(8, 0, 2));
    }

    ExplosionRegressionReport startPowered(
            ServerLevel level,
            ServerPlayer player,
            SingleplayerTestVolume volume,
            String actor,
            BlockPos support
    ) {
        SingleplayerPlayerActionDriver actions = new SingleplayerPlayerActionDriver(level, player);
        BlockPos tnt = support.above();
        Set<BlockPos> witnesses = Set.of(
                tnt.north(),
                tnt.south(),
                tnt.east(),
                tnt.west()
        );

        this.trackedPlayerAction(actor, () -> {
            level.setBlock(support, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            for (BlockPos witness : witnesses) {
                level.setBlock(witness, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        });

        boolean placed = actions.placeAttemptAgainst(support, Direction.UP, Blocks.TNT);
        ExplosionRegressionReport report = new ExplosionRegressionReport(
                placed,
                false,
                tnt,
                Set.of(tnt),
                Set.copyOf(witnesses),
                Set.of(tnt)
        );
        return new ExplosionRegressionReport(
                placed,
                report.primedTntPresent(level),
                tnt,
                Set.of(tnt),
                Set.copyOf(witnesses),
                Set.of(tnt)
        );
    }

    ExplosionRegressionReport startPoweredChain(
            ServerLevel level,
            ServerPlayer player,
            SingleplayerTestVolume volume,
            String actor,
            BlockPos support
    ) {
        SingleplayerPlayerActionDriver actions = new SingleplayerPlayerActionDriver(level, player);
        BlockPos firstTnt = support.above();
        BlockPos secondTnt = firstTnt.east();
        BlockPos thirdTnt = secondTnt.east();
        Set<BlockPos> tntBlocks = Set.of(firstTnt, secondTnt, thirdTnt);
        Set<BlockPos> witnesses = Set.of(
                firstTnt.south(),
                secondTnt.south(),
                thirdTnt.south(),
                thirdTnt.east(),
                secondTnt.north()
        );

        this.trackedPlayerAction(actor, () -> {
            level.setBlock(support, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            level.setBlock(secondTnt, Blocks.TNT.defaultBlockState(), 3);
            level.setBlock(thirdTnt, Blocks.TNT.defaultBlockState(), 3);
            for (BlockPos witness : witnesses) {
                level.setBlock(witness, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        });

        boolean placed = actions.placeAttemptAgainst(support, Direction.UP, Blocks.TNT);
        ExplosionRegressionReport report = new ExplosionRegressionReport(
                placed,
                false,
                firstTnt,
                Set.copyOf(tntBlocks),
                Set.copyOf(witnesses),
                Set.of(firstTnt)
        );
        return new ExplosionRegressionReport(
                placed,
                report.primedTntPresent(level),
                firstTnt,
                Set.copyOf(tntBlocks),
                Set.copyOf(witnesses),
                Set.of(firstTnt)
        );
    }

    ExplosionRegressionReport start(
            ServerLevel level,
            ServerPlayer player,
            SingleplayerTestVolume volume,
            String actor,
            BlockPos support
    ) {
        SingleplayerPlayerActionDriver actions = new SingleplayerPlayerActionDriver(level, player);
        BlockPos tnt = support.above();
        Set<BlockPos> witnesses = Set.of(
                tnt.north(),
                tnt.south(),
                tnt.east(),
                tnt.west()
        );

        this.trackedPlayerAction(actor, () -> {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            for (BlockPos witness : witnesses) {
                level.setBlock(witness, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        });

        boolean placed = actions.placeAgainst(support, Direction.UP, Blocks.TNT, tnt);
        boolean ignited = actions.useItemOn(tnt, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL, 1));
        return new ExplosionRegressionReport(placed, ignited, tnt, Set.copyOf(witnesses));
    }

    private void trackedPlayerAction(String actor, Runnable runnable) {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, actor, true);
        try {
            runnable.run();
        } finally {
            WorldMutationContext.popSource();
        }
    }

    record ExplosionRegressionReport(
            boolean placed,
            boolean ignited,
            BlockPos tntPos,
            Set<BlockPos> tntBlocks,
            Set<BlockPos> witnessBlocks,
            Set<BlockPos> consumedTntBlocks
    ) {

        ExplosionRegressionReport(boolean placed, boolean ignited, BlockPos tntPos, Set<BlockPos> witnessBlocks) {
            this(placed, ignited, tntPos, Set.of(tntPos), witnessBlocks, Set.of());
        }

        ExplosionRegressionReport {
            tntBlocks = tntBlocks == null ? Set.of(tntPos) : Set.copyOf(tntBlocks);
            witnessBlocks = witnessBlocks == null ? Set.of() : Set.copyOf(witnessBlocks);
            consumedTntBlocks = consumedTntBlocks == null ? Set.of() : Set.copyOf(consumedTntBlocks);
        }

        Set<BlockPoint> expectedUndoRedoBlocks() {
            LinkedHashSet<BlockPoint> blocks = new LinkedHashSet<>();
            for (BlockPos tntBlock : this.tntBlocks) {
                if (!this.consumedTntBlocks.contains(tntBlock)) {
                    blocks.add(BlockPoint.from(tntBlock));
                }
            }
            for (BlockPos witness : this.witnessBlocks) {
                blocks.add(BlockPoint.from(witness));
            }
            return Set.copyOf(blocks);
        }

        boolean exploded(ServerLevel level) {
            return this.tntBlocks.stream().allMatch(pos -> level.getBlockState(pos).isAir())
                    && this.witnessBlocks.stream().anyMatch(pos -> level.getBlockState(pos).isAir())
                    && !this.primedTntPresent(level);
        }

        boolean restoredAfterUndo(ServerLevel level) {
            return this.persistentTntBlocksRestored(level)
                    && this.consumedTntBlocksCleared(level)
                    && !this.primedTntPresent(level)
                    && this.witnessBlocks.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.OAK_PLANKS));
        }

        boolean removedAfterRedo(ServerLevel level) {
            return this.tntBlocks.stream().allMatch(pos -> level.getBlockState(pos).isAir())
                    && this.witnessBlocks.stream().anyMatch(pos -> level.getBlockState(pos).isAir());
        }

        boolean primedTntPresent(ServerLevel level) {
            AABB bounds = new AABB(this.tntPos).inflate(2.0D);
            return !level.getEntities((Entity) null, bounds, entity -> entity.getType() == EntityType.TNT).isEmpty();
        }

        boolean restoredBeforeExplosionUndo(ServerLevel level) {
            return this.persistentTntBlocksRestored(level)
                    && this.consumedTntBlocksCleared(level)
                    && !this.primedTntPresent(level)
                    && this.witnessBlocks.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.OAK_PLANKS));
        }

        private boolean persistentTntBlocksRestored(ServerLevel level) {
            return this.tntBlocks.stream()
                    .filter(pos -> !this.consumedTntBlocks.contains(pos))
                    .allMatch(pos -> level.getBlockState(pos).is(Blocks.TNT));
        }

        private boolean consumedTntBlocksCleared(ServerLevel level) {
            return this.consumedTntBlocks.stream().allMatch(pos -> level.getBlockState(pos).isAir());
        }
    }
}
