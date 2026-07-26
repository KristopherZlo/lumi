package io.github.lumi.minecraft.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

/** Makes durable entity NBT stable across a vanilla save/load cycle. */
final class MinecraftEntityNbtCanonicalizer {
    private static final float FULL_ROTATION = 360.0F;

    CompoundTag normalize(CompoundTag source) {
        return normalize(source, entityType(source));
    }

    CompoundTag normalize(CompoundTag source, EntityType<?> type) {
        CompoundTag normalized = Objects.requireNonNull(source, "source").copy();
        normalizeEntity(normalized, type);
        normalized.remove("id");
        normalized.remove("UUID");
        return normalized;
    }

    private void normalizeEntity(CompoundTag entity, EntityType<?> type) {
        normalizeRotation(entity.getListOrEmpty("Rotation"));
        normalizeLastHurtByMob(entity);
        normalizeAttributes(entity, type);
        ListTag passengers = entity.getListOrEmpty("Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            passengers.getCompound(index).ifPresent(passenger ->
                    normalizeEntity(passenger, entityType(passenger)));
        }
    }

    private static void normalizeRotation(ListTag rotation) {
        if (rotation.size() != 2
                || !(rotation.get(0) instanceof FloatTag yaw)
                || !(rotation.get(1) instanceof FloatTag pitch)
                || !Float.isFinite(yaw.value())
                || !Float.isFinite(pitch.value())) {
            return;
        }
        rotation.set(0, FloatTag.valueOf(yaw.value() % FULL_ROTATION));
        rotation.set(1, FloatTag.valueOf(Math.clamp(
                pitch.value() % FULL_ROTATION, -90.0F, 90.0F)));
    }

    private static void normalizeLastHurtByMob(CompoundTag entity) {
        if (!entity.contains("last_hurt_by_mob")) {
            return;
        }
        entity.remove("HurtByTimestamp");
        entity.getInt("ticks_since_last_hurt_by_mob").ifPresent(ticks ->
                entity.putInt("ticks_since_last_hurt_by_mob", -Math.abs(ticks)));
    }

    private static void normalizeAttributes(CompoundTag entity, EntityType<?> type) {
        if (!entity.contains("attributes")) {
            return;
        }
        ListTag attributes = entity.getListOrEmpty("attributes");
        var entries = new ArrayList<CompoundTag>(attributes.size());
        var ids = new HashSet<String>();
        for (int index = 0; index < attributes.size(); index++) {
            CompoundTag entry = attributes.getCompound(index).orElse(null);
            String id = entry == null ? "" : entry.getStringOr("id", "");
            if (id.isEmpty() || !ids.add(id)) {
                return;
            }
            entries.add(entry);
        }
        AttributeSupplier defaults = defaultAttributes(type);
        if (defaults != null) {
            entries.removeIf(entry -> isDefaultAttribute(entry, defaults));
        }
        if (entries.isEmpty()) {
            entity.remove("attributes");
            return;
        }
        entries.sort(Comparator.comparing(entry -> entry.getStringOr("id", "")));
        attributes.clear();
        for (int index = 0; index < entries.size(); index++) {
            attributes.add(entries.get(index));
        }
    }

    private static boolean isDefaultAttribute(
            CompoundTag entry, AttributeSupplier defaults) {
        if (!entry.getListOrEmpty("modifiers").isEmpty()) {
            return false;
        }
        String id = entry.getStringOr("id", "");
        final Identifier identifier;
        try {
            identifier = Identifier.parse(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        var attribute = BuiltInRegistries.ATTRIBUTE.get(identifier).orElse(null);
        var base = entry.getDouble("base").orElse(null);
        return attribute != null && base != null && defaults.hasAttribute(attribute)
                && Double.compare(base, defaults.getBaseValue(attribute)) == 0;
    }

    @SuppressWarnings("unchecked")
    private static AttributeSupplier defaultAttributes(EntityType<?> type) {
        if (type == null || !DefaultAttributes.hasSupplier(type)) {
            return null;
        }
        return DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
    }

    private static EntityType<?> entityType(CompoundTag entity) {
        String id = entity.getStringOr("id", "");
        if (id.isEmpty()) {
            return null;
        }
        try {
            return BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id)).orElse(null);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
