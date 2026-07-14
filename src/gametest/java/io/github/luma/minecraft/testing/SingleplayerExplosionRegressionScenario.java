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
        return this.start(level, player, volume, actor, volume.min().offset(13, 8, 13));
    }

    ExplosionRegressionReport startWithCleanFixture(
            ServerLevel level,
            ServerPlayer player,
            SingleplayerTestVolume volume,
            BlockPos support
    ) {
        SingleplayerPlayerActionDriver actions = new SingleplayerPlayerActionDriver(level, player);
        BlockPos tnt = support.above();
        Set<BlockPos> witnesses = Set.of(tnt.north(), tnt.south(), tnt.east(), tnt.west());
        this.installFixture(() -> {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            for (BlockPos witness : witnesses) {
                level.setBlock(witness, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        });

        boolean placed = actions.placeAgainst(support, Direction.UP, Blocks.TNT, tnt);
        boolean ignited = actions.useItemOn(tnt, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL, 1));
        return new ExplosionRegressionReport(placed, ignited, tnt, Set.copyOf(witnesses));
    }

    ExplosionRegressionReport startPowered(
            ServerLevel level,
            ServerPlayer player,
            SingleplayerTestVolume volume,
            String actor,
            BlockPos support
    ) {
        SingleplayerPlayerActionDriver actions = new SingleplayerPlayerActionDriver(level, player);
        BlockPos trigger = support.above();
        BlockPos tnt = trigger.east();
        Set<BlockPos> witnesses = Set.of(
                tnt.north(),
                tnt.south(),
                tnt.east()
        );

        this.installFixture(() -> {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(trigger, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(tnt, Blocks.TNT.defaultBlockState(), 3);
            for (BlockPos witness : witnesses) {
                level.setBlock(witness, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        });

        boolean placed = actions.placeAgainst(support, Direction.UP, Blocks.REDSTONE_BLOCK, trigger);
        ExplosionRegressionReport report = new ExplosionRegressionReport(
                placed,
                false,
                tnt,
                Set.of(tnt),
                Set.copyOf(witnesses),
                Set.of(),
                trigger
        );
        return new ExplosionRegressionReport(
                placed,
                report.primedTntPresent(level),
                tnt,
                Set.of(tnt),
                Set.copyOf(witnesses),
                Set.of(),
                trigger
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
        BlockPos trigger = support.above();
        BlockPos firstTnt = trigger.east();
        LinkedHashSet<BlockPos> tntBlocks = new LinkedHashSet<>();
        LinkedHashSet<BlockPos> witnesses = new LinkedHashSet<>();
        for (int east = 0; east < 5; east++) {
            tntBlocks.add(firstTnt.east(east));
            tntBlocks.add(firstTnt.east(east).south());
            witnesses.add(firstTnt.east(east).north());
            witnesses.add(firstTnt.east(east).south(2));
        }
        witnesses.add(firstTnt.east(5));
        witnesses.add(firstTnt.east(5).south());

        this.installFixture(() -> {
            level.setBlock(support, Blocks.OBSIDIAN.defaultBlockState(), 3);
            level.setBlock(trigger, Blocks.AIR.defaultBlockState(), 3);
            for (BlockPos tnt : tntBlocks) {
                level.setBlock(tnt.below(), Blocks.OBSIDIAN.defaultBlockState(), 3);
                level.setBlock(tnt, Blocks.TNT.defaultBlockState(), 3);
            }
            for (BlockPos witness : witnesses) {
                level.setBlock(witness, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        });

        boolean placed = actions.placeAgainst(support, Direction.UP, Blocks.REDSTONE_BLOCK, trigger);
        ExplosionRegressionReport report = new ExplosionRegressionReport(
                placed,
                false,
                firstTnt,
                Set.copyOf(tntBlocks),
                Set.copyOf(witnesses),
                Set.of(),
                trigger
        );
        return new ExplosionRegressionReport(
                placed,
                report.primedTntPresent(level),
                firstTnt,
                Set.copyOf(tntBlocks),
                Set.copyOf(witnesses),
                Set.of(),
                trigger
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

        this.installFixture(() -> {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            for (BlockPos witness : witnesses) {
                level.setBlock(witness, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        });

        boolean placed = actions.placeAgainst(support, Direction.UP, Blocks.TNT, tnt);
        boolean ignited = actions.useItemOn(tnt, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL, 1));
        return new ExplosionRegressionReport(placed, ignited, tnt, Set.copyOf(witnesses));
    }

    private void installFixture(Runnable runnable) {
        try (
                WorldMutationContext.SourceFrame ignoredSource =
                        WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                WorldMutationContext.SuppressionFrame ignoredCapture =
                        WorldMutationContext.pushCaptureSuppression()
        ) {
            runnable.run();
        }
    }

    record ExplosionRegressionReport(
            boolean placed,
            boolean ignited,
            BlockPos tntPos,
            Set<BlockPos> tntBlocks,
            Set<BlockPos> witnessBlocks,
            Set<BlockPos> consumedTntBlocks,
            BlockPos triggerBlock
    ) {

        ExplosionRegressionReport(boolean placed, boolean ignited, BlockPos tntPos, Set<BlockPos> witnessBlocks) {
            this(placed, ignited, tntPos, Set.of(tntPos), witnessBlocks, Set.of(), null);
        }

        ExplosionRegressionReport {
            tntBlocks = tntBlocks == null ? Set.of(tntPos) : Set.copyOf(tntBlocks);
            witnessBlocks = witnessBlocks == null ? Set.of() : Set.copyOf(witnessBlocks);
            consumedTntBlocks = consumedTntBlocks == null ? Set.of() : Set.copyOf(consumedTntBlocks);
        }

        Set<BlockPoint> expectedUndoRedoBlocks(ServerLevel level) {
            LinkedHashSet<BlockPoint> blocks = new LinkedHashSet<>();
            for (BlockPos tntBlock : this.tntBlocks) {
                if (!this.consumedTntBlocks.contains(tntBlock)) {
                    blocks.add(BlockPoint.from(tntBlock));
                }
            }
            for (BlockPos witness : this.witnessBlocks) {
                if (!level.getBlockState(witness).is(Blocks.OAK_PLANKS)) {
                    blocks.add(BlockPoint.from(witness));
                }
            }
            if (this.triggerBlock != null && !level.getBlockState(this.triggerBlock).isAir()) {
                blocks.add(BlockPoint.from(this.triggerBlock));
            }
            return Set.copyOf(blocks);
        }

        boolean exploded(ServerLevel level) {
            return this.tntBlocks.stream().allMatch(pos -> level.getBlockState(pos).isAir())
                    && this.witnessBlocks.stream().anyMatch(pos -> !level.getBlockState(pos).is(Blocks.OAK_PLANKS))
                    && !this.primedTntPresent(level);
        }

        Set<BlockPoint> destroyedWitnessBlocks(ServerLevel level) {
            LinkedHashSet<BlockPoint> blocks = new LinkedHashSet<>();
            for (BlockPos witness : this.witnessBlocks) {
                if (!level.getBlockState(witness).is(Blocks.OAK_PLANKS)) {
                    blocks.add(BlockPoint.from(witness));
                }
            }
            return Set.copyOf(blocks);
        }

        boolean restoredAfterUndo(ServerLevel level) {
            return this.persistentTntBlocksRestored(level)
                    && this.consumedTntBlocksCleared(level)
                    && this.triggerBlockCleared(level)
                    && !this.primedTntPresent(level)
                    && this.witnessBlocks.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.OAK_PLANKS));
        }

        boolean removedAfterRedo(ServerLevel level) {
            return this.tntBlocks.stream().allMatch(pos -> level.getBlockState(pos).isAir())
                    && this.witnessBlocks.stream().anyMatch(pos -> !level.getBlockState(pos).is(Blocks.OAK_PLANKS));
        }

        boolean primedTntPresent(ServerLevel level) {
            AABB bounds = this.tntBlocks.stream()
                    .map(AABB::new)
                    .reduce(new AABB(this.tntPos), AABB::minmax)
                    .inflate(2.0D);
            return !level.getEntities((Entity) null, bounds, entity -> entity.getType() == EntityType.TNT).isEmpty();
        }

        boolean restoredBeforeExplosionUndo(ServerLevel level) {
            return this.persistentTntBlocksRestored(level)
                    && this.consumedTntBlocksCleared(level)
                    && this.triggerBlockCleared(level)
                    && !this.primedTntPresent(level)
                    && this.witnessBlocks.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.OAK_PLANKS));
        }

        String restorationMismatches(ServerLevel level) {
            List<String> mismatches = new ArrayList<>();
            if (this.triggerBlock != null && !level.getBlockState(this.triggerBlock).isAir()) {
                mismatches.add("trigger=" + level.getBlockState(this.triggerBlock));
            }
            for (BlockPos pos : this.tntBlocks) {
                if (!level.getBlockState(pos).is(Blocks.TNT)) {
                    mismatches.add("tnt " + pos.toShortString() + "=" + level.getBlockState(pos));
                }
            }
            if (this.primedTntPresent(level)) {
                mismatches.add("primed TNT remains");
            }
            return mismatches.isEmpty() ? "none" : String.join("; ", mismatches);
        }

        private boolean persistentTntBlocksRestored(ServerLevel level) {
            return this.tntBlocks.stream()
                    .filter(pos -> !this.consumedTntBlocks.contains(pos))
                    .allMatch(pos -> level.getBlockState(pos).is(Blocks.TNT));
        }

        private boolean consumedTntBlocksCleared(ServerLevel level) {
            return this.consumedTntBlocks.stream().allMatch(pos -> level.getBlockState(pos).isAir());
        }

        private boolean triggerBlockCleared(ServerLevel level) {
            return this.triggerBlock == null || level.getBlockState(this.triggerBlock).isAir();
        }
    }
}
