package io.github.luma.mixin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerGamePacketListenerMixinTest {

    @Test
    void interactPacketsRememberEntityStateBeforeVanillaHandling() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/ServerGamePacketListenerMixin.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("luma$rememberInteractedEntity(packet);"));
        assertTrue(source.contains("packet.getTarget(level)"));
        assertTrue(source.contains("rememberCurrentPlayerAction(target, level)"));
        int rememberCall = source.indexOf("luma$rememberInteractedEntity(packet);");
        assertTrue(rememberCall < source.indexOf("original.call(packet);", rememberCall));
    }

    @Test
    void creeperInteractPacketsLogRememberedCausalContext() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/ServerGamePacketListenerMixin.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("target instanceof Creeper"));
        assertTrue(source.contains("LumaLoadLog.event(\"creeper-explosion\", \"interact-context\""));
        assertTrue(source.contains("remembered=\" + remembered"));
    }

    @Test
    void playerOwnedDamageSourcesCreateEntityCausalContext() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/LivingEntityCausalContextMixin.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("damageSource.getEntity()"));
        assertTrue(source.contains("attacker instanceof ServerPlayer player"));
        assertTrue(source.contains("WorldMutationContext.pushPlayerSource"));
    }
}
