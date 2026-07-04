package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectileCausalContextMixinTest {

    @Test
    void playerProjectilesCarryCausalContextFromSpawnToHit() throws Exception {
        String lifecycle = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityLifecycleMixin.java"));
        String living = Files.readString(Path.of("src/main/java/io/github/luma/mixin/LivingEntityCausalContextMixin.java"));
        String projectile = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ProjectileCausalContextMixin.java"));
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));

        assertTrue(living.contains("@WrapMethod(method = \"stopUsingItem\")"));
        assertTrue(lifecycle.contains("entity instanceof Projectile"));
        assertTrue(lifecycle.contains("rememberCurrentActionIfAbsent(entity, level)"));
        assertTrue(lifecycle.contains("rememberSpawn(entity, (ServerLevel) (Object) this)"));
        assertTrue(lifecycle.contains("LumaLoadLog.event(\"tnt-replay\", \"primed-tnt-spawn\""));
        assertTrue(lifecycle.contains("accepted=\" + accepted"));
        assertTrue(lifecycle.contains("frozen=\" + LUMA_REPLAY_TICK_SUPPRESSION.shouldFreezeWorldTick(level)"));
        assertTrue(projectile.contains("@Mixin(Projectile.class)"));
        assertTrue(projectile.contains("@WrapMethod(method = \"onHit\")"));
        assertTrue(projectile.contains("pushIfPresent(projectile, level)"));
        assertTrue(mixins.contains("\"ProjectileCausalContextMixin\""));
    }
}
