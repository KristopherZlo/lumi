package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorStandCausalContextMixinTest {

    @Test
    void armorStandDamageRemembersCausalSnapshotBeforeVanillaDropsEquipment() throws Exception {
        Path mixinPath = Path.of("src/main/java/io/github/luma/mixin/ArmorStandCausalContextMixin.java");

        assertTrue(Files.exists(mixinPath), "ArmorStand overrides hurtServer and needs its own causal hook");

        String source = Files.readString(mixinPath);
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));
        int remember = source.indexOf("luma$rememberDamageContext(entity, serverLevel, damageSource)");
        int originalCall = source.indexOf("original.call(serverLevel, damageSource, amount)");

        assertTrue(source.contains("@Mixin(ArmorStand.class)"));
        assertTrue(source.contains("@WrapMethod(method = \"hurtServer\")"));
        assertTrue(remember >= 0);
        assertTrue(remember < originalCall);
        assertTrue(mixins.contains("\"ArmorStandCausalContextMixin\""));
    }
}
