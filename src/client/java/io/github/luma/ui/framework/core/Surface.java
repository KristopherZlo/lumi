package io.github.luma.ui.framework.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

public final class Surface {

    public static final Surface PANEL_INSET = Surface.flat(0xEA101113).and(Surface.outline(0xFF2B2A2F));

    private final List<Layer> layers;

    private Surface(List<Layer> layers) {
        this.layers = List.copyOf(layers);
    }

    public static Surface flat(int color) {
        return new Surface(List.of(new Layer(LayerType.FILL, color)));
    }

    public static Surface outline(int color) {
        return new Surface(List.of(new Layer(LayerType.OUTLINE, color)));
    }

    public Surface and(Surface other) {
        List<Layer> combined = new ArrayList<>(this.layers);
        combined.addAll(other.layers);
        return new Surface(combined);
    }

    public void render(GuiGraphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        for (Layer layer : this.layers) {
            if (layer.type == LayerType.FILL) {
                graphics.fill(x, y, x + width, y + height, layer.color);
            } else {
                graphics.renderOutline(x, y, width, height, layer.color);
            }
        }
    }

    private enum LayerType {
        FILL,
        OUTLINE
    }

    private record Layer(LayerType type, int color) {
    }
}
