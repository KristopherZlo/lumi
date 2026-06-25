package io.github.luma.ui;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

public final class TagSuggestionComponent extends BaseUIComponent {

    private static final int MAX_SUGGESTIONS = 4;
    private static final int ROW_HEIGHT = 14;
    private static final int FILL = 0xF2090A0C;
    private static final int BORDER = 0xFF555158;
    private static final int HOVER = 0xFF23272C;

    private final Supplier<String> text;
    private final Supplier<List<String>> knownTags;
    private final boolean appendComma;
    private final Consumer<String> accept;

    public TagSuggestionComponent(
            Supplier<String> text,
            Supplier<List<String>> knownTags,
            boolean appendComma,
            Consumer<String> accept
    ) {
        this.text = text;
        this.knownTags = knownTags;
        this.appendComma = appendComma;
        this.accept = accept;
        this.sizing(Sizing.fill(100), Sizing.fixed(this.suggestionHeight()));
    }

    public void refresh() {
        this.verticalSizing(Sizing.fixed(this.suggestionHeight()));
    }

    @Override
    protected int determineHorizontalContentSize(Sizing sizing) {
        return 120;
    }

    @Override
    protected int determineVerticalContentSize(Sizing sizing) {
        return this.suggestionHeight();
    }

    @Override
    public void update(float delta, int mouseX, int mouseY) {
        super.update(delta, mouseX, mouseY);
        this.cursorStyle(this.hoverIndex(mouseX, mouseY) >= 0 ? CursorStyle.HAND : CursorStyle.NONE);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.onMouseDown(click, doubled);
        }
        int index = (int) (click.y() / ROW_HEIGHT);
        List<String> suggestions = this.suggestions();
        if (index < 0 || index >= suggestions.size()) {
            return super.onMouseDown(click, doubled);
        }
        this.accept.accept(TagInputSupport.acceptSuggestion(this.text.get(), suggestions.get(index), this.appendComma));
        this.refresh();
        return true;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        List<String> suggestions = this.suggestions();
        if (suggestions.isEmpty()) {
            return;
        }

        graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, FILL);
        graphics.drawRectOutline(this.x, this.y, this.width, this.height, BORDER);
        Font font = Minecraft.getInstance().font;
        int hovered = this.hoverIndex(mouseX, mouseY);
        for (int index = 0; index < suggestions.size(); index++) {
            int rowY = this.y + index * ROW_HEIGHT;
            if (index == hovered) {
                graphics.fill(this.x + 1, rowY + 1, this.x + this.width - 1, rowY + ROW_HEIGHT - 1, HOVER);
            }
            graphics.drawString(font, "#" + suggestions.get(index), this.x + 5, rowY + 3, 0xFFEDE9DF, false);
        }
    }

    private int hoverIndex(int mouseX, int mouseY) {
        if (!this.isInBoundingBox(mouseX, mouseY)) {
            return -1;
        }
        int index = (mouseY - this.y) / ROW_HEIGHT;
        return index >= 0 && index < this.suggestions().size() ? index : -1;
    }

    private int suggestionHeight() {
        return this.suggestions().size() * ROW_HEIGHT;
    }

    private List<String> suggestions() {
        return TagInputSupport.suggestions(this.text.get(), this.knownTags.get(), MAX_SUGGESTIONS);
    }
}
