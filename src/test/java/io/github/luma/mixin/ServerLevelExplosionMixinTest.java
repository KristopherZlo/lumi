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
    void baseLevelExplosionsUseRememberedEntityCausalContextOnServers() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/LevelExplosionMixin.java"));

        assertTrue(source.contains("EntityCausalContextRegistry"));
        assertTrue(source.contains("(Object) this instanceof ServerLevel level"));
        assertTrue(source.contains("pushIfPresent(entity, level, WorldMutationSource.EXPLOSION)"));
    }
}
