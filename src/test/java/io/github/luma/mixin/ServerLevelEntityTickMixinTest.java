package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

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
        int rememberedFrame = source.indexOf("luma$pushRememberedCausalAction(entity, source)", tickMethod);
        int fallbackFrame = source.indexOf("luma$pushEntityTickSource(entity, source)", tickMethod);

        assertTrue(rememberedFrame > tickMethod);
        assertTrue(fallbackFrame > rememberedFrame);
    }

    @Test
    void fallingBlockTicksReuseRememberedCausalAction() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("entity instanceof FallingBlockEntity"));
        assertTrue(source.contains("source == WorldMutationSource.MOB || source == WorldMutationSource.FALLING_BLOCK"));
        assertTrue(source.contains("pushIfPresent(entity, (ServerLevel) (Object) this, source)"));
    }

    @Test
    void causalCarrierTicksRestoreActionBeforeSourceFallback() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));
        int tickMethod = source.indexOf("private void luma$wrapEntityTick");
        int causalFrame = source.indexOf("luma$pushRememberedCausalAction(entity, source)", tickMethod);
        int sourceFallback = source.indexOf("if (source == null &&", causalFrame);

        assertTrue(causalFrame > tickMethod);
        assertTrue(sourceFallback > causalFrame);
        assertTrue(source.contains("pushIfPresent(entity, (ServerLevel) (Object) this)"));
    }

    @Test
    void carriesPrimedTntContextAcrossEntityTick() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("DeferredWorldMutationContexts.pushSource(entity)"));
    }

    @Test
    void tntReplayFreezeSkipsOnlyPrimedTntEntityTicks() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));
        int tickMethod = source.indexOf("private void luma$wrapEntityTick");
        int freezeCheck = source.indexOf("this.luma$shouldFreezePrimedTnt(entity)", tickMethod);
        int originalCall = source.indexOf("original.call(entity)", tickMethod);

        assertTrue(source.contains("WorldReplayTickSuppression"));
        assertTrue(freezeCheck > tickMethod);
        assertTrue(freezeCheck < originalCall);
        assertTrue(source.contains("entity instanceof PrimedTnt"));
        assertTrue(source.contains("shouldFreezeWorldTick((ServerLevel) (Object) this)"));
    }
}
