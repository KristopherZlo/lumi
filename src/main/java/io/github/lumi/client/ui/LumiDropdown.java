package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Compact bounded selector shared by branch and settings views. */
class LumiDropdown<T> extends LumiButton {
    static final int ROW_HEIGHT = 18;
    private static final int MAX_ROWS = 8;
    private static final long HOVER_MILLIS = 120;
    private static final Identifier CHEVRON_DOWN = icon("chevron-down");
    private static final Identifier CHEVRON_UP = icon("chevron-up");
    private final List<T> options;
    private final T selected;
    private final Function<T, Component> label;
    private final Consumer<T> select;
    private final int visibleRows;
    private final boolean opensAbove;
    private final float[] hover;
    private final LumiScrollbar scrollbar;
    private int scroll;
    private boolean open;
    private long lastRenderNanos = System.nanoTime();

    LumiDropdown(
            int x, int y, int width,
            int availableAbove, int availableBelow,
            List<T> options, T selected,
            Function<T, Component> label, Consumer<T> select) {
        super(x, y, width, ROW_HEIGHT,
                selectedLabel(label, selected),
                button -> ((LumiDropdown<?>) button).open ^= true,
                Kind.NORMAL);
        this.options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (this.options.isEmpty()) {
            throw new IllegalArgumentException("Dropdown options cannot be empty");
        }
        this.selected = Objects.requireNonNull(selected, "selected");
        this.label = Objects.requireNonNull(label, "label");
        this.select = Objects.requireNonNull(select, "select");
        opensAbove = opensAbove(
                availableAbove, availableBelow, this.options.size());
        int available = opensAbove ? availableAbove : availableBelow;
        visibleRows = visibleRows(available, this.options.size());
        hover = new float[this.options.size()];
        int selectedIndex = java.util.stream.IntStream.range(0, this.options.size())
                .filter(index -> Objects.equals(this.options.get(index), selected))
                .findFirst().orElse(0);
        scroll = Math.max(0, Math.min(
                selectedIndex, this.options.size() - visibleRows));
        scrollbar = new LumiScrollbar(
                x, menuY(), width, visibleRows * ROW_HEIGHT, () -> { });
        configureScrollbar();
    }

    static LumiDropdown<String> branches(
            int x, int y, int width, int availableHeight,
            List<HistorySnapshotPayload.Branch> branches,
            String selected, Consumer<String> select) {
        return new LumiDropdown<>(
                x, y, width, 0, availableHeight,
                branches.stream().map(HistorySnapshotPayload.Branch::name).toList(),
                selected, value -> Component.literal(shortName(value)), select);
    }

    void open() {
        open = true;
    }

    boolean isOpen() {
        return open;
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderContents(graphics, mouseX, mouseY, partialTick);
        graphics.blit(RenderPipelines.GUI_TEXTURED,
                open ? CHEVRON_UP : CHEVRON_DOWN,
                getX() + getWidth() - 16, getY() + 3,
                0, 0, 12, 12, 24, 24, 24, 24);
        long now = System.nanoTime();
        long elapsedMillis = Math.min(
                HOVER_MILLIS, (now - lastRenderNanos) / 1_000_000L);
        lastRenderNanos = now;
        if (!open) return;
        var font = Minecraft.getInstance().font;
        for (int row = 0; row < visibleRows; row++) {
            int index = scroll + row;
            int y = menuY() + row * ROW_HEIGHT;
            T option = options.get(index);
            boolean rowHovered = mouseX >= getX()
                    && mouseX < getX() + getWidth() - LumiScrollbar.GUTTER_WIDTH
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            hover[index] = hoverProgress(
                    hover[index], rowHovered, elapsedMillis);
            boolean optionSelected = Objects.equals(option, selected);
            LumiTheme.outlined(graphics, getX(), y, getWidth(), ROW_HEIGHT,
                    LumiTheme.WINDOW, optionSelected
                            ? LumiTheme.ACCENT : LumiTheme.INSET_BORDER);
            int hoverAlpha = Math.round(40 * hover[index]);
            if (hoverAlpha > 0) {
                graphics.fill(getX() + 1, y + 1,
                        getX() + getWidth() - LumiScrollbar.GUTTER_WIDTH,
                        y + ROW_HEIGHT - 1,
                        hoverAlpha << 24 | 0x00ffffff);
            }
            graphics.drawString(font,
                    font.plainSubstrByWidth(label.apply(option).getString(),
                            getWidth() - 12 - LumiScrollbar.GUTTER_WIDTH),
                    getX() + 5, y + 5,
                    optionSelected ? LumiTheme.ACCENT : LumiTheme.TEXT,
                    false);
        }
        configureScrollbar();
        scrollbar.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (open && scrollbar.mouseClicked(click, doubled)) return true;
        if (open && menuHovered(click.x(), click.y())) {
            int index = scroll + ((int) click.y() - menuY()) / ROW_HEIGHT;
            open = false;
            select.accept(options.get(index));
            return true;
        }
        if (super.mouseClicked(click, doubled)) return true;
        open = false;
        return false;
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        if (!open || !menuHovered(mouseX, mouseY)) return false;
        int maximum = Math.max(0, options.size() - visibleRows);
        scroll = Math.max(0, Math.min(maximum,
                scroll + (verticalAmount < 0 ? 1 : -1)));
        configureScrollbar();
        return true;
    }

    @Override
    public boolean mouseDragged(
            MouseButtonEvent click, double deltaX, double deltaY) {
        return open && scrollbar.mouseDragged(click, deltaX, deltaY)
                || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return scrollbar.mouseReleased(click) || super.mouseReleased(click);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return super.isMouseOver(mouseX, mouseY)
                || open && menuHovered(mouseX, mouseY);
    }

    private boolean menuHovered(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= menuY()
                && mouseY < menuY() + visibleRows * ROW_HEIGHT;
    }

    private int menuY() {
        return opensAbove
                ? getY() - visibleRows * ROW_HEIGHT : getY() + getHeight();
    }

    private void configureScrollbar() {
        scrollbar.configure(
                options.size(), visibleRows, scroll, value -> scroll = value);
    }

    static float hoverProgress(
            float current, boolean hovered, long elapsedMillis) {
        float step = Math.max(0L, elapsedMillis) / (float) HOVER_MILLIS;
        return Math.max(0F, Math.min(1F,
                current + (hovered ? step : -step)));
    }

    static int visibleRows(int availableHeight, int optionCount) {
        return Math.min(optionCount, Math.min(MAX_ROWS,
                Math.max(1, availableHeight / ROW_HEIGHT)));
    }

    static boolean opensAbove(
            int availableAbove, int availableBelow, int optionCount) {
        int wantedHeight = Math.min(MAX_ROWS, optionCount) * ROW_HEIGHT;
        return availableBelow < wantedHeight && availableAbove > availableBelow;
    }

    static String shortName(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static <T> Component selectedLabel(
            Function<T, Component> label, T selected) {
        return Objects.requireNonNull(
                Objects.requireNonNull(label, "label").apply(
                        Objects.requireNonNull(selected, "selected")),
                "selected label");
    }

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, "textures/gui/icons/" + name + ".png");
    }
}
