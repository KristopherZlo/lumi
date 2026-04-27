package io.github.luma.ui.framework.component;

import io.github.luma.ui.framework.core.Sizing;
import io.github.luma.ui.framework.core.UIComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class TextBoxComponent extends UIComponent {

    private final EditBox editBox;
    private final TextChangedEvent changedEvent = new TextChangedEvent();

    TextBoxComponent(Sizing horizontalSizing, String value) {
        this.editBox = new EditBox(Minecraft.getInstance().font, 0, 0, 120, 18, Component.empty());
        this.editBox.setBordered(true);
        this.editBox.setTextShadow(false);
        this.editBox.setMaxLength(512);
        this.editBox.setValue(value == null ? "" : value);
        this.editBox.setResponder(this.changedEvent::emit);
        this.sizing(horizontalSizing, Sizing.fixed(18));
    }

    public TextChangedEvent onChanged() {
        return this.changedEvent;
    }

    public void setHint(Component hint) {
        this.editBox.setHint(hint);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        this.editBox.setFocused(focused);
    }

    @Override
    public int measureWidth(int availableWidth) {
        return Math.max(52, availableWidth);
    }

    @Override
    public int measureHeight(int availableWidth, int availableHeight) {
        return 18;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.editBox.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean handled = this.editBox.mouseClicked(event, doubleClick);
        this.setFocused(this.editBox.isFocused());
        return handled;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return this.editBox.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return this.editBox.charTyped(event);
    }

    @Override
    protected void onBoundsChanged() {
        this.editBox.setX(this.x());
        this.editBox.setY(this.y());
        this.editBox.setSize(this.width(), this.height());
    }

    public static final class TextChangedEvent {
        private final List<Consumer<String>> subscribers = new ArrayList<>();

        public void subscribe(Consumer<String> subscriber) {
            this.subscribers.add(subscriber);
        }

        private void emit(String value) {
            for (Consumer<String> subscriber : this.subscribers) {
                subscriber.accept(value);
            }
        }
    }
}
