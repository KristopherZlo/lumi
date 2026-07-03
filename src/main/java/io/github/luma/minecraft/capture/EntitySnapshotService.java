package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.EntityPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueOutput;

public final class EntitySnapshotService {

    private static final String CREEPER_ENTITY_TYPE = "minecraft:creeper";
    private static final short DEFAULT_CREEPER_FUSE = 30;

    private final EntitySnapshotSanitizer sanitizer = new EntitySnapshotSanitizer();

    public EntityPayload capture(ServerLevel level, Entity entity) {
        return this.capture(level, entity, true);
    }

    public EntityPayload captureExact(ServerLevel level, Entity entity) {
        return this.capture(level, entity, false);
    }

    private EntityPayload capture(ServerLevel level, Entity entity, boolean normalize) {
        if (level == null || entity == null || entity instanceof ServerPlayer) {
            return null;
        }

        this.sanitizer.sanitize(entity);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        try {
            if (!entity.save(output)) {
                return null;
            }
        } catch (RuntimeException exception) {
            LumaMod.LOGGER.warn("Skipped unsafe entity snapshot for {}", this.entityType(entity), exception);
            return null;
        }

        CompoundTag tag = normalize ? normalizeForHistory(output.buildResult()) : output.buildResult().copy();
        if (!tag.contains("id")) {
            tag.putString("id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        }
        if (!tag.contains("UUID")) {
            tag.putString("UUID", entity.getUUID().toString());
        }
        return new EntityPayload(tag);
    }

    public static CompoundTag normalizeForHistory(CompoundTag source) {
        CompoundTag tag = source == null ? new CompoundTag() : source.copy();
        if (tag.contains("DeathTime")) {
            tag.putShort("DeathTime", (short) 0);
        }
        if (tag.contains("HurtTime")) {
            tag.putShort("HurtTime", (short) 0);
        }
        if (tag.contains("Fire")) {
            tag.putShort("Fire", (short) 0);
        }
        if (tag.contains("Health") && tag.getFloatOr("Health", 1.0F) <= 0.0F) {
            tag.putFloat("Health", 1.0F);
        }
        if (CREEPER_ENTITY_TYPE.equals(tag.getString("id").orElse(""))) {
            tag.putBoolean("ignited", false);
            tag.putShort("Fuse", DEFAULT_CREEPER_FUSE);
        }
        return tag;
    }

    private String entityType(Entity entity) {
        if (entity == null || entity.getType() == null) {
            return "unknown";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
