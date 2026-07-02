package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLevelEntityTickMixinTest {

    @Test
    void tracksBlockChangingMobTicksAsMobSources() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        for (String entityClass : new String[]{
                "Creeper",
                "EnderDragon",
                "EnderMan",
                "Ghast",
                "Ravager",
                "Rabbit",
                "Sheep",
                "Silverfish",
                "Villager",
                "Vindicator",
                "WitherBoss",
                "WitherSkull",
                "Zombie"
        }) {
            assertTrue(source.contains("instanceof " + entityClass), entityClass + " must be captured as a MOB mutation source");
        }
    }

    @Test
    void aggroedMobTicksCarryPlayerCausalAction() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("entity instanceof Mob mob"));
        assertTrue(source.contains("mob.getTarget() instanceof ServerPlayer player"));
        assertTrue(source.contains("WorldMutationContext.pushPlayerSource("));
    }

    @Test
    void mobProjectilesCarryOwnerTargetCausalAction() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("entity instanceof Projectile projectile"));
        assertTrue(source.contains("projectile.getOwner() instanceof Mob owner"));
        assertTrue(source.contains("owner.getTarget() instanceof ServerPlayer player"));
    }

    @Test
    void rememberedCreeperInteractionCanCarryCausalMobAction() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/github/luma/mixin/ServerLevelEntityTickMixin.java"));

        assertTrue(source.contains("EntityCausalContextRegistry"));
        assertTrue(source.contains("pushIfPresent(entity, (ServerLevel) (Object) this, source)"));
    }
}
