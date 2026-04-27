package io.github.luma.ui.framework.component;

import io.github.luma.ui.framework.core.Sizing;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class UIComponents {

    private UIComponents() {
    }

    public static LabelComponent label(Component text) {
        return new LabelComponent(text);
    }

    public static ButtonComponent button(Component text, Consumer<ButtonComponent> onPress) {
        return new ButtonComponent(text, onPress);
    }

    public static TextBoxComponent textBox(Sizing horizontalSizing, String value) {
        return new TextBoxComponent(horizontalSizing, value);
    }

    public static CheckboxComponent checkbox(Component label) {
        return new CheckboxComponent(label);
    }

    public static TextureComponent texture(
            Identifier texture,
            int u,
            int v,
            int regionWidth,
            int regionHeight,
            int textureWidth,
            int textureHeight
    ) {
        return new TextureComponent(texture, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    public static ItemComponent item(ItemStack stack) {
        return new ItemComponent(stack);
    }
}
