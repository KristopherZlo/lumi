package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCausalContextRegistryTest {

    private final EntityCausalContextRegistry registry = EntityCausalContextRegistry.getInstance();

    @Test
    void remembersCapturableDamageContexts() {
        assertTrue(this.registry.canRememberSource(WorldMutationSource.PLAYER));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.MOB));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.EXPLOSION));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.EXPLOSIVE));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.FALLING_BLOCK));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.BLOCK_UPDATE));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.PISTON));
    }

    @Test
    void ignoresInternalDamageContexts() {
        assertFalse(this.registry.canRememberSource(WorldMutationSource.SYSTEM));
        assertFalse(this.registry.canRememberSource(WorldMutationSource.RESTORE));
    }

    @Test
    void emptyLookupsReuseTheInactiveContextFrame() {
        EntityCausalContextRegistry.ContextFrame first = this.registry.pushIfPresent(null, null);
        EntityCausalContextRegistry.ContextFrame second = this.registry.pushIfPresent(null, null);

        first.close();

        assertSame(first, second);
        assertFalse(second.active());
    }

    @Test
    void causalContextsAreScopedByDimensionAndEntityUuid() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/capture/EntityCausalContextRegistry.java")
        );

        assertTrue(source.contains("Map<EntityContextKey, EntityCausalContext> contexts"));
        assertTrue(source.contains("new EntityContextKey(level.dimension().identifier().toString(), entity.getUUID())"));
    }
}
