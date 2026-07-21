package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Compact scrollable branch selector shared by history views. */
final class LumiBranchDropdown extends LumiButton {
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_ROWS = 8;
    private static final long HOVER_MILLIS = 120;
    private static final Identifier CHEVRON_DOWN = icon("chevron-down");
    private static final Identifier CHEVRON_UP = icon("chevron-up");
    private final List<HistorySnapshotPayload.Branch> branches;
    private final String selected;
    private final Consumer<String> select;
    private final int visibleRows;
    private final float[] hover;
    private final LumiScrollbar scrollbar;
    private int scroll;
    private boolean open;
    private long lastRenderNanos = System.nanoTime();

    LumiBranchDropdown(
            int x, int y, int width, int availableHeight,
            List<HistorySnapshotPayload.Branch> branches,
            String selected,
            Consumer<String> select) {
        super(x, y, width, ROW_HEIGHT,
                Component.literal(shortName(selected)),
                button -> ((LumiBranchDropdown) button).open ^= true,
                Kind.NORMAL);
        this.branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        this.selected = Objects.requireNonNull(selected, "selected");
        this.select = Objects.requireNonNull(select, "select");
        visibleRows = visibleRows(availableHeight, branches.size());
        hover = new float[branches.size()];
        int selectedIndex = java.util.stream.IntStream.range(0, branches.size())
                .filter(index -> branches.get(index).name().equals(selected))
                .findFirst().orElse(0);
        scroll = Math.max(0, Math.min(
                selectedIndex, branches.size() - visibleRows));
        scrollbar = new LumiScrollbar(
                x, y + ROW_HEIGHT, width, visibleRows * ROW_HEIGHT, () -> { });
        configureScrollbar();
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
            String branch = branches.get(index).name();
            boolean rowHovered = mouseX >= getX()
                    && mouseX < getX() + getWidth() - LumiScrollbar.GUTTER_WIDTH
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            hover[index] = hoverProgress(
                    hover[index], rowHovered, elapsedMillis);
            LumiTheme.outlined(graphics, getX(), y, getWidth(), ROW_HEIGHT,
                    LumiTheme.WINDOW, branch.equals(selected)
                            ? LumiTheme.ACCENT : LumiTheme.INSET_BORDER);
            int hoverAlpha = Math.round(40 * hover[index]);
            if (hoverAlpha > 0) {
                graphics.fill(getX() + 1, y + 1,
                        getX() + getWidth() - LumiScrollbar.GUTTER_WIDTH,
                        y + ROW_HEIGHT - 1,
                        hoverAlpha << 24 | 0x00ffffff);
            }
            graphics.drawString(font,
                    font.plainSubstrByWidth(shortName(branch),
                            getWidth() - 12 - LumiScrollbar.GUTTER_WIDTH),
                    getX() + 5, y + 5,
                    branch.equals(selected) ? LumiTheme.ACCENT : LumiTheme.TEXT,
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
            select.accept(branches.get(index).name());
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
        int maximum = Math.max(0, branches.size() - visibleRows);
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
        return getY() + getHeight();
    }

    private void configureScrollbar() {
        scrollbar.configure(
                branches.size(), visibleRows, scroll, value -> scroll = value);
    }

    static float hoverProgress(
            float current, boolean hovered, long elapsedMillis) {
        float step = Math.max(0L, elapsedMillis) / (float) HOVER_MILLIS;
        return Math.max(0F, Math.min(1F,
                current + (hovered ? step : -step)));
    }

    static int visibleRows(int availableHeight, int branchCount) {
        return Math.min(branchCount, Math.min(MAX_ROWS,
                Math.max(1, availableHeight / ROW_HEIGHT)));
    }

    static String shortName(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, "textures/gui/icons/" + name + ".png");
    }
}
