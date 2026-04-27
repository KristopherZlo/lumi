package io.github.luma.ui;

import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class MaterialEntryView {

    private MaterialEntryView() {
    }

    public static FlowLayout row(String blockId, Component summary) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        row.child(icon(blockId));

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        text.child(LumaUi.caption(summary));
        row.child(text);
        return row;
    }

    private static UIComponent icon(String blockId) {
        ItemStack stack = stackFor(blockId);
        if (stack.isEmpty()) {
            return LumaUi.chip(Component.literal("-"));
        }

        var icon = UIComponents.item(stack);
        icon.sizing(Sizing.fixed(16), Sizing.fixed(16));
        return icon;
    }

    private static ItemStack stackFor(String blockId) {
        if (blockId == null || blockId.isBlank() || "minecraft:air".equals(blockId)) {
            return ItemStack.EMPTY;
        }

        Identifier id = Identifier.tryParse(blockId);
        if (id == null) {
            return ItemStack.EMPTY;
        }

        Block block = BuiltInRegistries.BLOCK.get(id)
                .map(net.minecraft.core.Holder.Reference::value)
                .orElse(Blocks.AIR);
        if (block == Blocks.AIR) {
            return ItemStack.EMPTY;
        }

        Item item = block.asItem();
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }

        return new ItemStack(item);
    }
}
