package io.github.luma.ui.framework.container;

import io.github.luma.ui.framework.core.HorizontalAlignment;
import io.github.luma.ui.framework.core.Insets;
import io.github.luma.ui.framework.core.Sizing;
import io.github.luma.ui.framework.core.Surface;
import io.github.luma.ui.framework.core.UIComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public final class FlowLayout extends UIComponent {

    public enum Direction {
        VERTICAL,
        HORIZONTAL
    }

    private final Direction direction;
    private final boolean wraps;
    private final List<UIComponent> children = new ArrayList<>();
    private Insets padding = Insets.of(0);
    private int gap;
    private Surface surface;
    private HorizontalAlignment horizontalAlignment = HorizontalAlignment.LEFT;

    FlowLayout(Direction direction, boolean wraps, Sizing horizontalSizing, Sizing verticalSizing) {
        this.direction = direction;
        this.wraps = wraps;
        this.sizing(horizontalSizing, verticalSizing);
    }

    public FlowLayout child(UIComponent component) {
        this.children.add(component);
        return this;
    }

    public List<UIComponent> children() {
        return Collections.unmodifiableList(this.children);
    }

    public void clearChildren() {
        this.children.clear();
    }

    public FlowLayout padding(Insets padding) {
        this.padding = padding;
        return this;
    }

    public FlowLayout gap(int gap) {
        this.gap = Math.max(0, gap);
        return this;
    }

    public FlowLayout surface(Surface surface) {
        this.surface = surface;
        return this;
    }

    public FlowLayout horizontalAlignment(HorizontalAlignment horizontalAlignment) {
        this.horizontalAlignment = horizontalAlignment;
        return this;
    }

    @Override
    public void layout(int x, int y, int availableWidth, int availableHeight) {
        int measuredWidth = this.measureWidth(availableWidth);
        int width = this.horizontalSizing().resolve(availableWidth, measuredWidth);
        int measuredHeight = this.measureHeight(width, availableHeight);
        int height = this.verticalSizing().resolve(availableHeight, measuredHeight);
        this.setBounds(x, y, width, height);
        if (this.direction == Direction.VERTICAL) {
            this.layoutVertical();
        } else if (this.wraps) {
            this.layoutWrappedHorizontal();
        } else {
            this.layoutHorizontal();
        }
    }

    @Override
    public int measureWidth(int availableWidth) {
        int innerAvailable = Math.max(0, availableWidth - this.padding.horizontal());
        if (this.horizontalSizing().mode() != Sizing.Mode.CONTENT) {
            return Math.max(0, availableWidth);
        }
        int width = 0;
        if (this.direction == Direction.VERTICAL) {
            for (UIComponent child : this.children) {
                width = Math.max(width, child.measureWidth(innerAvailable));
            }
        } else {
            for (UIComponent child : this.children) {
                width += child.measureWidth(innerAvailable);
            }
            width += Math.max(0, this.children.size() - 1) * this.gap;
        }
        return width + this.padding.horizontal();
    }

    @Override
    public int measureHeight(int availableWidth, int availableHeight) {
        int innerWidth = Math.max(0, availableWidth - this.padding.horizontal());
        if (this.verticalSizing().mode() != Sizing.Mode.CONTENT) {
            return Math.max(0, availableHeight);
        }
        int height = this.direction == Direction.VERTICAL
                ? this.measureVerticalHeight(innerWidth, availableHeight)
                : this.measureHorizontalHeight(innerWidth, availableHeight);
        return height + this.padding.vertical();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.surface != null) {
            this.surface.render(graphics, this.x(), this.y(), this.width(), this.height());
        }
        for (UIComponent child : this.children) {
            child.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int index = this.children.size() - 1; index >= 0; index -= 1) {
            UIComponent child = this.children.get(index);
            if (child.isMouseOver(event.x(), event.y()) && child.mouseClicked(event, doubleClick)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        for (int index = this.children.size() - 1; index >= 0; index -= 1) {
            UIComponent child = this.children.get(index);
            if (child.isMouseOver(mouseX, mouseY)
                    && child.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        UIComponent focused = this.focusedDescendant();
        return focused != null && focused != this && focused.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        UIComponent focused = this.focusedDescendant();
        return focused != null && focused != this && focused.charTyped(event);
    }

    @Override
    public UIComponent focusedDescendant() {
        UIComponent focused = super.focusedDescendant();
        if (focused != null) {
            return focused;
        }
        for (UIComponent child : this.children) {
            UIComponent childFocus = child.focusedDescendant();
            if (childFocus != null) {
                return childFocus;
            }
        }
        return null;
    }

    @Override
    public void clearFocusDeep() {
        super.clearFocusDeep();
        for (UIComponent child : this.children) {
            child.clearFocusDeep();
        }
    }

    private void layoutVertical() {
        int innerX = this.x() + this.padding.left();
        int innerY = this.y() + this.padding.top();
        int innerWidth = Math.max(0, this.width() - this.padding.horizontal());
        int innerHeight = Math.max(0, this.height() - this.padding.vertical());
        int fixedHeight = 0;
        int flexibleWeight = 0;

        for (UIComponent child : this.children) {
            if (child.verticalSizing().flexible()) {
                flexibleWeight += Math.max(1, child.verticalSizing().value());
            } else {
                fixedHeight += child.measureHeight(innerWidth, innerHeight);
            }
        }
        fixedHeight += Math.max(0, this.children.size() - 1) * this.gap;

        int remaining = Math.max(0, innerHeight - fixedHeight);
        int cursorY = innerY;
        for (UIComponent child : this.children) {
            int childHeight = child.verticalSizing().flexible() && flexibleWeight > 0
                    ? remaining * Math.max(1, child.verticalSizing().value()) / flexibleWeight
                    : child.measureHeight(innerWidth, innerHeight);
            child.layout(innerX, cursorY, innerWidth, childHeight);
            cursorY += child.height() + this.gap;
        }
    }

    private void layoutHorizontal() {
        int innerX = this.x() + this.padding.left();
        int innerY = this.y() + this.padding.top();
        int innerWidth = Math.max(0, this.width() - this.padding.horizontal());
        int innerHeight = Math.max(0, this.height() - this.padding.vertical());
        int fixedWidth = 0;
        int flexibleWeight = 0;

        for (UIComponent child : this.children) {
            if (child.horizontalSizing().flexible()) {
                flexibleWeight += Math.max(1, child.horizontalSizing().value());
            } else {
                fixedWidth += child.measureWidth(innerWidth);
            }
        }
        fixedWidth += Math.max(0, this.children.size() - 1) * this.gap;

        int remaining = Math.max(0, innerWidth - fixedWidth);
        int totalWidth = fixedWidth + remaining;
        int cursorX = switch (this.horizontalAlignment) {
            case CENTER -> innerX + Math.max(0, (innerWidth - totalWidth) / 2);
            case RIGHT -> innerX + Math.max(0, innerWidth - totalWidth);
            default -> innerX;
        };

        for (UIComponent child : this.children) {
            int childWidth = child.horizontalSizing().flexible() && flexibleWeight > 0
                    ? remaining * Math.max(1, child.horizontalSizing().value()) / flexibleWeight
                    : child.measureWidth(innerWidth);
            child.layout(cursorX, innerY, childWidth, innerHeight);
            cursorX += child.width() + this.gap;
        }
    }

    private void layoutWrappedHorizontal() {
        int innerX = this.x() + this.padding.left();
        int innerY = this.y() + this.padding.top();
        int innerWidth = Math.max(0, this.width() - this.padding.horizontal());
        int cursorX = innerX;
        int cursorY = innerY;
        int rowHeight = 0;

        for (UIComponent child : this.children) {
            int childWidth = child.horizontalSizing().flexible()
                    ? innerWidth
                    : Math.min(innerWidth, child.measureWidth(innerWidth));
            int childHeight = child.measureHeight(childWidth, this.height());
            if (cursorX > innerX && cursorX + childWidth > innerX + innerWidth) {
                cursorX = innerX;
                cursorY += rowHeight + this.gap;
                rowHeight = 0;
            }
            child.layout(cursorX, cursorY, childWidth, childHeight);
            cursorX += child.width() + this.gap;
            rowHeight = Math.max(rowHeight, child.height());
        }
    }

    private int measureVerticalHeight(int innerWidth, int availableHeight) {
        int height = 0;
        for (UIComponent child : this.children) {
            height += child.measureHeight(innerWidth, availableHeight);
        }
        height += Math.max(0, this.children.size() - 1) * this.gap;
        return height;
    }

    private int measureHorizontalHeight(int innerWidth, int availableHeight) {
        if (!this.wraps) {
            int height = 0;
            for (UIComponent child : this.children) {
                height = Math.max(height, child.measureHeight(innerWidth, availableHeight));
            }
            return height;
        }

        int cursorX = 0;
        int rowHeight = 0;
        int totalHeight = 0;
        for (UIComponent child : this.children) {
            int childWidth = child.horizontalSizing().flexible()
                    ? innerWidth
                    : Math.min(innerWidth, child.measureWidth(innerWidth));
            int childHeight = child.measureHeight(childWidth, availableHeight);
            if (cursorX > 0 && cursorX + childWidth > innerWidth) {
                totalHeight += rowHeight + this.gap;
                cursorX = 0;
                rowHeight = 0;
            }
            cursorX += childWidth + this.gap;
            rowHeight = Math.max(rowHeight, childHeight);
        }
        return totalHeight + rowHeight;
    }
}
