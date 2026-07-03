package io.github.luma.mixin;

import io.github.luma.minecraft.capture.ExplosiveEntityContextRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLevelEntityTickMixinTest {

    @Test
    void tracksBlockChangingMobTicksAsMobSources() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("entity instanceof Mob"));
        assertTrue(source.contains("entity instanceof EnderDragon"));
        assertTrue(source.contains("entity instanceof WitherSkull"));
        assertTrue(source.contains("entity instanceof Projectile projectile"));
        assertTrue(source.contains("projectile.getOwner() instanceof Mob"));
    }

    @Test
    void aggroedMobTicksDoNotCreatePlayerActions() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("entity instanceof Mob"));
        assertFalse(source.contains("mob.getTarget() instanceof ServerPlayer player"));
        assertFalse(source.contains("WorldMutationContext.pushPlayerSource("));
        assertFalse(source.contains("rememberCurrentPlayerActionIfAbsent"));
    }

    @Test
    void mobProjectilesDoNotCreatePlayerActionsFromOwnerTargets() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("entity instanceof Projectile projectile"));
        assertTrue(source.contains("projectile.getOwner() instanceof Mob"));
        assertFalse(source.contains("owner.getTarget() instanceof ServerPlayer player"));
    }

    @Test
    void rememberedCreeperInteractionCanCarryCausalMobAction() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("EntityCausalContextRegistry"));
        assertTrue(source.contains("pushIfPresent(entity, (ServerLevel) (Object) this, source)"));
    }

    @Test
    void rememberedCausalMobActionIsTriedBeforeOpeningFallbackMobAction() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));
        int tickMethod = source.indexOf("private void luma$wrapEntityTick");
        int rememberedFrame = source.indexOf("luma$pushRememberedCausalMobAction(entity, source)", tickMethod);
        int fallbackFrame = source.indexOf("luma$pushEntityTickSource(entity, source)", tickMethod);

        assertTrue(rememberedFrame > tickMethod);
        assertTrue(fallbackFrame > rememberedFrame);
    }

    @Test
    void carriesPrimedTntContextAcrossEntityTick() throws NoSuchFieldException, NoSuchMethodException {
        Field registry = ServerLevelEntityTickMixin.class.getDeclaredField("LUMA_EXPLOSIVE_CONTEXTS");
        assertEquals(ExplosiveEntityContextRegistry.class, registry.getType());

        Method method = ServerLevelEntityTickMixin.class.getDeclaredMethod(
                "luma$pushRememberedExplosiveAction",
                Entity.class
        );
        assertEquals(Boolean.TYPE, method.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isPrivate(method.getModifiers()));
    }
}
