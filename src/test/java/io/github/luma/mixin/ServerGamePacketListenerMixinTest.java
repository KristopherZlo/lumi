package io.github.luma.mixin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerGamePacketListenerMixinTest {

    @Test
    void interactPacketsOpenPlayerSourceAroundVanillaHandling() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/ServerGamePacketListenerMixin.java"),
                StandardCharsets.UTF_8
        );

        int method = source.indexOf("luma$wrapInteract");
        int helperCall = source.indexOf("this.luma$callWithPlayerSource(packet, original);", method);

        assertTrue(helperCall > method);
        assertFalse(source.contains("packet.getTarget(level)"));
        assertFalse(source.contains("rememberCurrentPlayerAction(target, level)"));
    }

    @Test
    void useItemOnPacketsOpenPlayerSourceForExtendedReachPlacements() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/ServerGamePacketListenerMixin.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("ServerboundUseItemOnPacket"));
        assertTrue(source.contains("method = \"handleUseItemOn\""));

        int method = source.indexOf("luma$wrapUseItemOn");
        int helperCall = source.indexOf("this.luma$callWithPlayerSource(packet, original);", method);

        assertTrue(method > 0);
        assertTrue(helperCall > method);
    }

    @Test
    void playerActionPacketsOpenPlayerSourceForAxiomBulldozerBreaks() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/ServerGamePacketListenerMixin.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("ServerboundPlayerActionPacket"));
        assertTrue(source.contains("method = \"handlePlayerAction\""));

        int method = source.indexOf("luma$wrapPlayerAction");
        int helperCall = source.indexOf("this.luma$callWithPlayerSource(packet, original);", method);

        assertTrue(method > 0);
        assertTrue(helperCall > method);
    }

    @Test
    void axiomSetBlockPacketSourceUsesPlayerIdentity() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/AxiomSetBlockPacketMixin.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("pushPacketSource(player)"));
    }

    @Test
    void axiomInfiniteReachCapturesRequestedTransitionsInsidePacketAction() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/AxiomSetBlockPacketMixin.java"),
                StandardCharsets.UTF_8
        );

        int sourceFrame = source.indexOf("captureService.pushPacketSource(player)");
        int before = source.indexOf("captureService.captureBefore(player, this.blocks, this.reason)");
        int original = source.indexOf("original.call(server, player)", before);
        int after = source.indexOf("captureService.captureAfter(packetCapture)", original);

        assertTrue(sourceFrame > 0);
        assertTrue(before > sourceFrame);
        assertTrue(original > before);
        assertTrue(after > original);
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

    @Test
    void lethalDamageQueuesPreDeathEntityReplay() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/LivingEntityCausalContextMixin.java"),
                StandardCharsets.UTF_8
        );

        int vanillaDamage = source.indexOf("original.call(serverLevel, damageSource, amount)");
        int deathCapture = source.indexOf("EntityMutationTracker.captureCausalDeath(entity)");

        assertTrue(source.contains("entity.isDeadOrDying()"));
        assertTrue(deathCapture > vanillaDamage);
    }

    @Test
    void nonLethalDamageKeepsExistingCreeperInteractionContext() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/io/github/luma/mixin/LivingEntityCausalContextMixin.java"),
                StandardCharsets.UTF_8
        );

        int existingContext = source.indexOf("boolean hadCausalContext = LUMA_ENTITY_CAUSAL_CONTEXTS.hasContext(entity, serverLevel);");
        int clearGuard = source.indexOf("!hadCausalContext");
        int clearCall = source.indexOf("LUMA_ENTITY_CAUSAL_CONTEXTS.clear(entity)");

        assertTrue(existingContext > 0);
        assertTrue(clearGuard > existingContext);
        assertTrue(clearGuard < clearCall);
    }
}
