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
}
