package io.github.lumi.client.ui;

import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** One full-row graph hit target; the visible node remains at its branch lane. */
final class LumiHistoryGraphNodeButton extends Button {
    private static final int[] LANE_COLORS = {
        0xff55c7ff, 0xffffb454, 0xffa78bfa, 0xff65d18a,
        0xffff718b, 0xfff0df68, 0xff64d8cb, 0xffd58cff
    };
    private final HistoryGraphLayout.Node node;
    private final int graphX;
    private final int laneSpacing;
    private final Integer zoneColor;

    LumiHistoryGraphNodeButton(
            int x, int y, int width,
            HistoryGraphLayout.Node node,
            int graphX, int laneSpacing, Integer zoneColor,
            Runnable open) {
        super(x, y, width, 28, Component.literal(VersionText.name(node.version())),
                ignored -> open.run(), DEFAULT_NARRATION);
        this.node = Objects.requireNonNull(node, "node");
        this.graphX = graphX;
        this.laneSpacing = laneSpacing;
        this.zoneColor = zoneColor;
        setTooltip(Tooltip.create(Component.translatable("luma.action.open_save")));
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (isHoveredOrFocused()) {
            graphics.fill(getX(), getY(), getX() + getWidth(),
                    getY() + getHeight(), 0x332f3438);
        }
        int nodeX = graphX + node.lane() * laneSpacing;
        int nodeY = getY() + getHeight() / 2;
        int color = zoneColor == null ? laneColor(node.lane()) : 0xff000000 | zoneColor;
        if (node.activeHead()) {
            LumiTheme.outlined(graphics, nodeX - 5, nodeY - 5, 10, 10,
                    LumiTheme.WINDOW, LumiTheme.ACCENT);
        }
        graphics.fill(nodeX - 3, nodeY - 3, nodeX + 4, nodeY + 4, color);
        var font = Minecraft.getInstance().font;
        int textX = graphX + Math.max(1, node.laneCount()) * laneSpacing + 12;
        int available = Math.max(0, getX() + getWidth() - textX - 4);
        graphics.drawString(font,
                font.plainSubstrByWidth(VersionText.name(node.version()), available),
                textX, getY() + 5, LumiTheme.TEXT, false);
        String marker = node.branchHeads().isEmpty()
                ? node.version().id().hex().substring(0, 7)
                : String.join(", ", node.branchHeads());
        graphics.drawString(font, font.plainSubstrByWidth(marker, available),
                textX, getY() + 16,
                node.activeHead() ? LumiTheme.ACCENT : LumiTheme.MUTED,
                false);
    }

    static int laneColor(int lane) {
        return LANE_COLORS[Math.floorMod(lane, LANE_COLORS.length)];
    }
}
