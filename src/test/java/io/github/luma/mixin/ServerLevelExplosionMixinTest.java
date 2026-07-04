package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLevelExplosionMixinTest {

    @Test
    void serverExplosionsUseRememberedEntityCausalContextBeforeAmbientFallback() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelExplosionMixin.java"));

        assertTrue(source.contains("EntityCausalContextRegistry"));
        assertTrue(source.contains("LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent("));
        assertTrue(source.contains("WorldMutationSource.EXPLOSION"));
        assertTrue(source.indexOf("LUMA_ENTITY_CAUSAL_CONTEXTS.pushIfPresent(")
                < source.indexOf("WorldMutationContext.pushSource(WorldMutationSource.EXPLOSION)"));
    }

    @Test
    void serverCreeperExplosionsLogSelectedCausalContext() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelExplosionMixin.java"));

        assertTrue(source.contains("entity instanceof Creeper"));
        assertTrue(source.contains("LumaLoadLog.event(\"creeper-explosion\", \"server-explode\""));
        assertTrue(source.contains("context=\" + contextKind"));
        assertTrue(source.contains("action=\" + WorldMutationContext.currentActionId()"));
    }

    @Test
    void serverPrimedTntExplosionsLogSelectedCausalContext() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelExplosionMixin.java"));

        assertTrue(source.contains("entity instanceof PrimedTnt"));
        assertTrue(source.contains("LumaLoadLog.event(\"tnt-replay\", \"server-explode\""));
        assertTrue(source.contains("context=\" + contextKind"));
        assertTrue(source.contains("access=\" + WorldMutationContext.currentAccessAllowed()"));
    }

    @Test
    void baseLevelExplosionsUseRememberedEntityCausalContextOnServers() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/LevelExplosionMixin.java"));

        assertTrue(source.contains("EntityCausalContextRegistry"));
        assertTrue(source.contains("(Object) this instanceof ServerLevel level"));
        assertTrue(source.contains("pushIfPresent(entity, level, WorldMutationSource.EXPLOSION)"));
    }

    @Test
    void baseLevelCreeperExplosionsLogSelectedCausalContext() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/LevelExplosionMixin.java"));

        assertTrue(source.contains("entity instanceof Creeper"));
        assertTrue(source.contains("LumaLoadLog.event(\"creeper-explosion\", \"level-explode\""));
        assertTrue(source.contains("context=\" + contextKind"));
    }

    @Test
    void baseLevelPrimedTntExplosionsLogSelectedCausalContext() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/LevelExplosionMixin.java"));

        assertTrue(source.contains("entity instanceof PrimedTnt"));
        assertTrue(source.contains("LumaLoadLog.event(\"tnt-replay\", \"level-explode\""));
        assertTrue(source.contains("context=\" + contextKind"));
        assertTrue(source.contains("access=\" + WorldMutationContext.currentAccessAllowed()"));
    }
}
