package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Creates deterministic block witnesses for unowned world incidents. */
final class SingleplayerWorldIncidentScenario {

    CreeperIncident startCreeper(ServerLevel level, SingleplayerTestVolume volume) {
        BlockPos center = volume.min().offset(5, 8, 13);
        Map<BlockPos, BlockState> fixture = new LinkedHashMap<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                fixture.put(center.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        this.installFixture(level, fixture);

        Creeper creeper = EntityType.CREEPER.create(level, EntitySpawnReason.COMMAND);
        if (creeper == null) {
            throw new IllegalStateException("Could not create creeper incident source");
        }
        creeper.setPos(center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D);
        level.explode(
                creeper,
                center.getX() + 0.5D,
                center.getY() + 0.5D,
                center.getZ() + 0.5D,
                3.0F,
                Level.ExplosionInteraction.MOB
        );
        return new CreeperIncident(fixture);
    }

    LightningIncident startLightning(ServerLevel level, SingleplayerTestVolume volume) {
        BlockPos strikePos = volume.min().offset(10, 8, 13);
        BlockState weatheredCopper = Blocks.WEATHERED_COPPER.defaultBlockState();
        this.installFixture(level, Map.of(strikePos, weatheredCopper));

        LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND);
        if (lightning == null) {
            throw new IllegalStateException("Could not create lightning incident source");
        }
        lightning.setPos(
                strikePos.getX() + 0.5D,
                strikePos.getY() + 1.0D,
                strikePos.getZ() + 0.5D
        );
        lightning.tick();
        return new LightningIncident(strikePos, weatheredCopper);
    }

    private void installFixture(ServerLevel level, Map<BlockPos, BlockState> fixture) {
        try (
                WorldMutationContext.SourceFrame ignoredSource =
                        WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                WorldMutationContext.SuppressionFrame ignoredCapture =
                        WorldMutationContext.pushCaptureSuppression()
        ) {
            fixture.forEach((pos, state) -> level.setBlock(pos, state, 3));
        }
    }

    record CreeperIncident(Map<BlockPos, BlockState> fixture) {

        CreeperIncident {
            fixture = Map.copyOf(fixture);
        }

        boolean changed(ServerLevel level) {
            return this.fixture.entrySet().stream()
                    .anyMatch(entry -> !level.getBlockState(entry.getKey()).equals(entry.getValue()));
        }

        boolean restored(ServerLevel level) {
            return this.fixture.entrySet().stream()
                    .allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
        }
    }

    record LightningIncident(BlockPos strikePos, BlockState originalState) {

        boolean changed(ServerLevel level) {
            return !level.getBlockState(this.strikePos).equals(this.originalState);
        }

        boolean restored(ServerLevel level) {
            return level.getBlockState(this.strikePos).equals(this.originalState);
        }
    }
}
