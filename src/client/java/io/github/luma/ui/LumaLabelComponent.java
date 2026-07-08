package io.github.luma.ui;

import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class LumaLabelComponent extends LabelComponent {

    public LumaLabelComponent(Component text) {
        super(text);
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        graphics.push().translate(0, LumaUiScale.targetPixelOffset());
        try {
            this.drawText((renderX, renderY, text, shadow, color) -> graphics.drawString(
                    Minecraft.getInstance().font,
                    text,
                    renderX,
                    renderY,
                    color.argb(),
                    shadow
            ));
        } finally {
            graphics.pop();
        }
    }
}
