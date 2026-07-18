package io.github.lumi.minecraft.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;

/** Makes durable entity NBT stable across a vanilla save/load cycle. */
final class MinecraftEntityNbtCanonicalizer {
    private static final float FULL_ROTATION = 360.0F;

    CompoundTag normalize(CompoundTag source) {
        CompoundTag normalized = Objects.requireNonNull(source, "source").copy();
        normalized.remove("id");
        normalized.remove("UUID");
        normalizeEntity(normalized);
        return normalized;
    }

    private void normalizeEntity(CompoundTag entity) {
        normalizeRotation(entity.getListOrEmpty("Rotation"));
        normalizeAttributes(entity.getListOrEmpty("attributes"));
        ListTag passengers = entity.getListOrEmpty("Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            passengers.getCompound(index).ifPresent(this::normalizeEntity);
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

    private static void normalizeAttributes(ListTag attributes) {
        if (attributes.size() < 2) {
            return;
        }
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
        entries.sort(Comparator.comparing(entry -> entry.getStringOr("id", "")));
        for (int index = 0; index < entries.size(); index++) {
            attributes.set(index, entries.get(index));
        }
    }
}
