package io.github.lumi.minecraft.world;

import java.io.IOException;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** Rejects missing content that vanilla otherwise silently discards while reading history. */
final class MinecraftHistoryNbtValidator {
    private static final Set<String> ITEM_LISTS = Set.of("Items", "Inventory", "ArmorItems", "HandItems",
            "items", "inventory", "armor_items", "hand_items", "minecraft:bundle_contents",
            "minecraft:charged_projectiles");
    private static final Set<String> ITEMS = Set.of("Item", "item", "item_stack", "SaddleItem", "ArmorItem",
            "DecorItem", "buy", "buyB", "sell", "mainhand", "offhand", "feet", "legs", "chest", "head", "body", "saddle");
    private final RegistryOps<Tag> itemOps;

    MinecraftHistoryNbtValidator(HolderLookup.Provider registries) {
        itemOps = RegistryOps.create(NbtOps.INSTANCE, registries);
    }

    void validate(Tag tag) throws IOException {
        if (tag instanceof ListTag list) {
            for (Tag child : list) validate(child);
        } else if (tag instanceof CompoundTag compound) {
            for (String key : compound.keySet()) {
                Tag child = compound.get(key);
                // Custom data is opaque; similarly named fields there are not vanilla references.
                if (key.equals("minecraft:custom_data")) continue;
                if (ITEM_LISTS.contains(key) && child instanceof ListTag list) {
                    for (Tag item : list) validateItem(item);
                } else if (ITEMS.contains(key) && child instanceof CompoundTag) {
                    validateItem(child);
                } else if ((key.equals("attributes") || key.equals("Attributes")) && child instanceof ListTag list) {
                    for (Tag attribute : list) {
                        if (!(attribute instanceof CompoundTag value)) throw new IOException("Invalid history attribute");
                        requireType(value.getStringOr("id", ""), BuiltInRegistries.ATTRIBUTE, "attribute");
                    }
                } else if (key.equals("memories") && child instanceof CompoundTag memories) {
                    for (String memory : memories.keySet()) {
                        requireType(memory, BuiltInRegistries.MEMORY_MODULE_TYPE, "memory module");
                    }
                }
                validate(child);
            }
        }
    }

    private void validateItem(Tag tag) throws IOException {
        if (!(tag instanceof CompoundTag item)) throw new IOException("Invalid history item stack");
        if (item.isEmpty()) return;
        // Container components wrap a stack in a slot/item record.
        if (!item.contains("id") && item.contains("item")) {
            validateItem(item.get("item"));
            return;
        }
        var result = ItemStack.CODEC.parse(itemOps, item);
        if (result.error().isPresent()) {
            throw new IOException("Invalid history item stack: " + result.error().orElseThrow().message());
        }
    }

    private static void requireType(String encoded, Registry<?> registry, String kind) throws IOException {
        Identifier id = Identifier.tryParse(encoded);
        if (id == null || registry.getOptional(id).isEmpty()) {
            throw new IOException("Missing history " + kind + ": " + encoded);
        }
    }
}
