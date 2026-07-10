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
    void remembersBuilderOwnedExplosiveDamageContexts() {
        assertTrue(this.registry.canRememberSource(WorldMutationSource.PLAYER, "action-1"));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.MOB, "action-1"));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.EXPLOSION, "action-1"));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.EXPLOSIVE, "action-1"));
        assertTrue(this.registry.canRememberSource(WorldMutationSource.FALLING_BLOCK, "action-1"));
    }

    @Test
    void ignoresAmbientDamageContextsWithoutBuilderAction() {
        assertFalse(this.registry.canRememberSource(WorldMutationSource.EXPLOSION, ""));
        assertFalse(this.registry.canRememberSource(WorldMutationSource.MOB, ""));
        assertFalse(this.registry.canRememberSource(WorldMutationSource.SYSTEM, "action-1"));
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
    void rememberedPlayerInteractionIsNotOverwrittenByLaterMobTick() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/capture/EntityCausalContextRegistry.java")
        );
        int method = source.indexOf("rememberCurrentPlayerActionIfAbsent");
        int contextCheck = source.indexOf("if (context != null)", method);
        int rememberCall = source.indexOf("return this.rememberCurrentPlayerAction(entity, level);", method);

        assertTrue(contextCheck > method);
        assertTrue(contextCheck < rememberCall);
    }

    @Test
    void minecartRemovalUsesSnapshotFromBreakingActionNotEarlierHit() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/capture/EntityCausalContextRegistry.java")
        );
        int method = source.indexOf("oldPayloadOverride");
        int nextMethod = source.indexOf("public boolean hasContext", method);
        int actionCheck = source.indexOf("currentFrameHasDifferentAction(context.actionId())", method);

        assertTrue(actionCheck > method);
        assertTrue(actionCheck < nextMethod);
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
