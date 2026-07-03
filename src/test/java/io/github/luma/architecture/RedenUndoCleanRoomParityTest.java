package io.github.luma.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedenUndoCleanRoomParityTest {

    @Test
    void redstoneAndTntUndoCarriersStayCleanRoomLumiImplementations() throws Exception {
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));

        assertTrue(mixins.contains("\"LevelTicksContextMixin\""));
        assertTrue(mixins.contains("\"ScheduledTickContextMixin\""));
        assertTrue(mixins.contains("\"ServerLevelBlockEventContextMixin\""));
        assertTrue(mixins.contains("\"BlockEventDataContextMixin\""));
        assertTrue(mixins.contains("\"PistonMovingBlockEntityContextMixin\""));
        assertTrue(mixins.contains("\"MovingPistonBlockTickerMixin\""));
        assertTrue(mixins.contains("\"TntBlockMixin\""));
        assertTrue(mixins.contains("\"ServerLevelEntityTickMixin\""));
        assertTrue(mixins.contains("\"ServerLevelExplosionMixin\""));
        assertTrue(mixins.contains("\"LevelExplosionMixin\""));
        assertTrue(mixins.contains("\"ProjectileCausalContextMixin\""));
        assertFalse(Files.exists(Path.of("src/main/java/com/github/zly2006/reden")));
        assertFalse(Files.exists(Path.of("src/main/resources/reden.mixins.json")));
    }

    @Test
    void mobFalloutStaysOnLumiEntityCausalPath() throws Exception {
        String entityTick = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));
        String explosion = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelExplosionMixin.java"));

        assertTrue(entityTick.contains("EntityCausalContextRegistry"));
        assertTrue(entityTick.contains("entity instanceof Mob"));
        assertTrue(entityTick.contains("projectile.getOwner() instanceof Mob"));
        assertFalse(entityTick.contains("owner.getTarget() instanceof ServerPlayer player"));
        assertTrue(explosion.contains("LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent"));
        assertFalse(explosion.contains("com.github.zly2006.reden"));
    }
}
