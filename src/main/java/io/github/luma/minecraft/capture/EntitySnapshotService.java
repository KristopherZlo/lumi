package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.EntityPayload;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueOutput;

public final class EntitySnapshotService {

    private static final Set<String> VOLATILE_TAGS = Set.of(
            "Air",
            "FallDistance",
            "Fire",
            "HurtByTimestamp",
            "HurtTime",
            "OnGround",
            "PortalCooldown",
            "TicksFrozen"
    );

    public EntityPayload capture(ServerLevel level, Entity entity) {
        if (level == null || entity == null || entity instanceof ServerPlayer) {
            return null;
        }

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        try {
            if (!entity.save(output)) {
                return null;
            }
        } catch (RuntimeException exception) {
            LumaMod.LOGGER.warn("Skipped unsafe entity snapshot for {}", this.entityType(entity), exception);
            return null;
        }

        CompoundTag tag = output.buildResult();
        for (String volatileTag : VOLATILE_TAGS) {
            tag.remove(volatileTag);
        }
        if (!tag.contains("id")) {
            tag.putString("id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        }
        if (!tag.contains("UUID")) {
            tag.putString("UUID", entity.getUUID().toString());
        }
        return new EntityPayload(tag);
    }

    private String entityType(Entity entity) {
        if (entity == null || entity.getType() == null) {
            return "unknown";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
