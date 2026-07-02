package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCausalContextRegistryTest {

    private final EntityCausalContextRegistry registry = EntityCausalContextRegistry.getInstance();

    @Test
    void remembersBuilderOwnedExplosiveDamageContexts() {
        assertTrue(this.registry.canRememberSource(WorldMutationSource.PLAYER, "action-1"));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.MOB, "action-1"));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.EXPLOSION, "action-1"));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.EXPLOSIVE, "action-1"));
    }

    @Test
    void ignoresAmbientDamageContextsWithoutBuilderAction() {
        assertFalse(this.registry.canRememberSource(WorldMutationSource.EXPLOSION, ""));
        assertFalse(this.registry.canRememberSource(WorldMutationSource.MOB, ""));
        assertFalse(this.registry.canRememberSource(WorldMutationSource.SYSTEM, "action-1"));
    }
}
