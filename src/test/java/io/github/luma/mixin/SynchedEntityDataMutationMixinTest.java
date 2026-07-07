package io.github.luma.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchedEntityDataMutationMixinTest {

    @Test
    void syncedEntityDataMutationsUseSharedEntityMutationCaptureAfterConstruction() throws Exception {
        String entityMixin = Files.readString(Path.of("src/main/java/io/github/luma/mixin/EntityMutationMixin.java"));
        String dataMixin = Files.readString(Path.of("src/main/java/io/github/luma/mixin/SynchedEntityDataMutationMixin.java"));
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));

        assertTrue(entityMixin.contains("implements EntityConstructionStateAccess"));
        assertTrue(entityMixin.contains("boolean luma$baseEntityConstructed()"));
        assertTrue(dataMixin.contains("@Mixin(SynchedEntityData.class)"));
        assertTrue(dataMixin.contains("@WrapMethod(method = \"set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V\")"));
        assertTrue(dataMixin.contains("!access.luma$baseEntityConstructed()"));
        assertTrue(dataMixin.contains("EntityMutationTracker.captureBefore(modifiedEntity)"));
        assertTrue(dataMixin.contains("rememberCurrentPlayerActionIfAbsent(entity, level)"));
        assertTrue(dataMixin.contains("EntityMutationTracker.captureUndoOnlyBefore(modifiedEntity)"));
        assertTrue(dataMixin.contains("EntityMutationTracker.captureAfter(modifiedEntity, pending)"));
        assertTrue(mixins.contains("\"SynchedEntityDataMutationMixin\""));
    }

    @Test
    void livingEntityEquipmentMutationsUseSharedEntityMutationCapture() throws Exception {
        Path mixinPath = Path.of("src/main/java/io/github/luma/mixin/LivingEntityEquipmentMutationMixin.java");
        String equipmentMixin = Files.readString(mixinPath);
        String mixins = Files.readString(Path.of("src/main/resources/lumi.mixins.json"));

        assertTrue(equipmentMixin.contains("@Mixin(LivingEntity.class)"));
        assertTrue(equipmentMixin.contains("@WrapMethod(method = \"setItemSlot\""));
        assertTrue(equipmentMixin.contains("EntityMutationTracker.captureBefore(entity)"));
        assertTrue(equipmentMixin.contains("EntityMutationTracker.captureAfter(entity, pending)"));
        assertTrue(mixins.contains("\"LivingEntityEquipmentMutationMixin\""));
    }
}
