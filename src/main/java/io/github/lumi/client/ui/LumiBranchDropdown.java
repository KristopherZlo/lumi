package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Compact scrollable branch selector shared by history views. */
final class LumiBranchDropdown extends LumiButton {
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_ROWS = 8;
    private final List<HistorySnapshotPayload.Branch> branches;
    private final String selected;
    private final Consumer<String> select;
    private final int visibleRows;
    private int scroll;
    private boolean open;

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
        int selectedIndex = java.util.stream.IntStream.range(0, branches.size())
                .filter(index -> branches.get(index).name().equals(selected))
                .findFirst().orElse(0);
        scroll = Math.max(0, Math.min(
                selectedIndex, branches.size() - visibleRows));
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderContents(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(Minecraft.getInstance().font, open ? "▲" : "▼",
                getX() + getWidth() - 11, getY() + 5, LumiTheme.MUTED, false);
        if (!open) return;
        var font = Minecraft.getInstance().font;
        for (int row = 0; row < visibleRows; row++) {
            int index = scroll + row;
            int y = menuY() + row * ROW_HEIGHT;
            String branch = branches.get(index).name();
            LumiTheme.outlined(graphics, getX(), y, getWidth(), ROW_HEIGHT,
                    LumiTheme.WINDOW, branch.equals(selected)
                            ? LumiTheme.ACCENT : LumiTheme.INSET_BORDER);
            graphics.drawString(font,
                    font.plainSubstrByWidth(shortName(branch), getWidth() - 12),
                    getX() + 5, y + 5,
                    branch.equals(selected) ? LumiTheme.ACCENT : LumiTheme.TEXT,
                    false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
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
        return true;
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

    static int visibleRows(int availableHeight, int branchCount) {
        return Math.min(branchCount, Math.min(MAX_ROWS,
                Math.max(1, availableHeight / ROW_HEIGHT)));
    }

    static String shortName(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }
}
