package io.github.luma.ui.graph;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.ProjectUiSupport;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

public final class CommitGraphComponent extends BaseUIComponent {

    private static final int[] LANE_COLORS = {
            0xFF58C7F3,
            0xFFFFB454,
            0xFF7CE38B,
            0xFFFF6E91,
            0xFFC792EA,
            0xFF82AAFF,
            0xFFFFCB6B,
            0xFF56D6C9
    };
    private static final int BACKDROP = 0x30071016;
    private static final int BORDER = 0x704D6070;
    private static final int TEXT_PRIMARY = 0xFFF3F7FA;
    private static final int TEXT_MUTED = 0xFF98A6B3;
    private static final int TEXT_BADGE = 0xFF0B1016;
    private static final int ROW_HEIGHT = 32;
    private static final int TOP_PADDING = 8;
    private static final int BOTTOM_PADDING = 8;
    private static final int SIDE_PADDING = 8;
    private static final int LEGEND_HEIGHT = 20;
    private static final int MIN_TEXT_WIDTH = 120;

    private final List<CommitGraphNode> nodes;
    private final Map<String, ProjectVariant> variantById;
    private final Map<Integer, List<ProjectVariant>> headVariantsByLane;
    private final int laneCount;
    private final int preferredHeight;

    public CommitGraphComponent(List<CommitGraphNode> nodes, List<ProjectVariant> variants) {
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.variantById = this.indexVariants(variants);
        this.headVariantsByLane = this.indexHeadVariants(this.nodes, this.variantById);
        this.laneCount = this.nodes.stream()
                .mapToInt(CommitGraphNode::laneCount)
                .max()
                .orElse(1);
        this.preferredHeight = TOP_PADDING
                + (this.headVariantsByLane.isEmpty() ? 0 : LEGEND_HEIGHT)
                + Math.max(1, this.nodes.size()) * ROW_HEIGHT
                + BOTTOM_PADDING;
        this.sizing(Sizing.fill(100), Sizing.fixed(this.preferredHeight));
    }

    @Override
    protected int determineHorizontalContentSize(Sizing sizing) {
        return 280;
    }

    @Override
    protected int determineVerticalContentSize(Sizing sizing) {
        return this.preferredHeight;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, BACKDROP);
        graphics.drawRectOutline(this.x, this.y, this.width, this.height, BORDER);
        if (this.nodes.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int graphX = this.x + SIDE_PADDING + 4;
        int textRight = this.x + this.width - SIDE_PADDING;
        int laneSpacing = this.laneSpacing();
        int graphWidth = Math.max(0, (this.laneCount - 1) * laneSpacing);
        int textX = Math.min(
                graphX + graphWidth + 18,
                Math.max(graphX + 42, textRight - MIN_TEXT_WIDTH)
        );
        int rowStartY = this.y + TOP_PADDING + (this.headVariantsByLane.isEmpty() ? 0 : LEGEND_HEIGHT);

        this.drawLegend(graphics, font, graphX, this.y + TOP_PADDING, textRight);
        this.drawParentConnectors(graphics, graphX, rowStartY, laneSpacing);
        this.drawLaneRuns(graphics, graphX, rowStartY, laneSpacing);
        this.drawRows(graphics, font, graphX, rowStartY, laneSpacing, textX, textRight);
    }

    private void drawLegend(OwoUIGraphics graphics, Font font, int startX, int y, int rightX) {
        int cursorX = startX;
        List<Map.Entry<Integer, List<ProjectVariant>>> entries = this.headVariantsByLane.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (Map.Entry<Integer, List<ProjectVariant>> entry : entries) {
            if (cursorX >= rightX - 24) {
                break;
            }
            String label = ProjectUiSupport.displayVariantName(entry.getValue().getFirst());
            cursorX = this.drawBadge(graphics, font, label, cursorX, y, this.laneColor(entry.getKey()), rightX) + 4;
        }
    }

    private void drawParentConnectors(OwoUIGraphics graphics, int graphX, int rowStartY, int laneSpacing) {
        for (CommitGraphNode node : this.nodes) {
            if (node.parentLane() < 0 || node.parentRowIndex() < 0 || node.parentLane() == node.lane()) {
                continue;
            }
            int nodeX = this.laneX(graphX, laneSpacing, node.lane());
            int nodeY = this.rowCenterY(rowStartY, node.rowIndex());
            int parentX = this.laneX(graphX, laneSpacing, node.parentLane());
            int parentY = this.rowCenterY(rowStartY, node.parentRowIndex());
            graphics.drawLine(nodeX, nodeY, parentX, parentY, 2.0D, Color.ofArgb(this.fade(this.laneColor(node.lane()), 0xCC)));
        }
    }

    private void drawLaneRuns(OwoUIGraphics graphics, int graphX, int rowStartY, int laneSpacing) {
        for (int lane = 0; lane < this.laneCount; lane++) {
            int firstRow = -1;
            int lastRow = -1;
            for (CommitGraphNode node : this.nodes) {
                if (!node.activeLanes().contains(lane)) {
                    continue;
                }
                if (firstRow < 0) {
                    firstRow = node.rowIndex();
                }
                lastRow = node.rowIndex();
            }
            if (firstRow < 0 || lastRow < 0) {
                continue;
            }
            int x = this.laneX(graphX, laneSpacing, lane);
            int y1 = this.rowCenterY(rowStartY, firstRow) - (ROW_HEIGHT / 2) + 4;
            int y2 = this.rowCenterY(rowStartY, lastRow) + (ROW_HEIGHT / 2) - 4;
            graphics.drawLine(x, y1, x, y2, 2.0D, Color.ofArgb(this.fade(this.laneColor(lane), 0x88)));
        }
    }

    private void drawRows(OwoUIGraphics graphics, Font font, int graphX, int rowStartY, int laneSpacing, int textX, int textRight) {
        for (CommitGraphNode node : this.nodes) {
            int rowY = this.rowCenterY(rowStartY, node.rowIndex());
            int laneColor = this.laneColor(node.lane());
            int nodeX = this.laneX(graphX, laneSpacing, node.lane());

            this.drawNode(graphics, nodeX, rowY, laneColor, node.activeHead());
            ProjectVersion version = node.version();
            int maxTextWidth = Math.max(24, textRight - textX);
            String title = this.trim(font, ProjectUiSupport.displayMessage(version), maxTextWidth);
            graphics.drawString(font, title, textX, rowY - 11, TEXT_PRIMARY, false);

            String meta = ProjectUiSupport.safeText(version.author())
                    + " | "
                    + ProjectUiSupport.formatTimestamp(version.createdAt())
                    + " | "
                    + version.id();
            graphics.drawString(font, this.trim(font, meta, maxTextWidth), textX, rowY + 1, TEXT_MUTED, false);

            int badgeX = textX + font.width(title) + 6;
            for (String variantId : node.headVariants()) {
                ProjectVariant variant = this.variantById.get(variantId);
                if (variant == null || badgeX >= textRight - 18) {
                    continue;
                }
                badgeX = this.drawBadge(
                        graphics,
                        font,
                        ProjectUiSupport.displayVariantName(variant),
                        badgeX,
                        rowY - 12,
                        laneColor,
                        textRight
                ) + 3;
            }
        }
    }

    private void drawNode(OwoUIGraphics graphics, int x, int y, int laneColor, boolean activeHead) {
        graphics.drawCircle(x, y, 24, activeHead ? 6.5D : 5.0D, Color.ofArgb(0xFF091018));
        if (activeHead) {
            graphics.drawRing(x, y, 24, 5.0D, 7.2D, Color.ofArgb(laneColor), Color.ofArgb(0xFFEAF5FF));
        }
        graphics.drawCircle(x, y, 24, activeHead ? 4.0D : 3.8D, Color.ofArgb(laneColor));
    }

    private int drawBadge(OwoUIGraphics graphics, Font font, String rawLabel, int x, int y, int color, int rightX) {
        String label = this.trim(font, rawLabel, Math.max(20, rightX - x - 10));
        int width = Math.min(rightX - x, font.width(label) + 8);
        if (width <= 0) {
            return x;
        }
        graphics.fill(x, y, x + width, y + 13, this.fade(color, 0xE6));
        graphics.drawRectOutline(x, y, width, 13, this.fade(color, 0xFF));
        graphics.drawString(font, label, x + 4, y + 2, TEXT_BADGE, false);
        return x + width;
    }

    private int laneSpacing() {
        if (this.laneCount <= 1) {
            return 0;
        }
        int available = Math.max(28, this.width - SIDE_PADDING - SIDE_PADDING - MIN_TEXT_WIDTH - 24);
        return Math.max(4, Math.min(16, available / Math.max(1, this.laneCount - 1)));
    }

    private int laneX(int graphX, int laneSpacing, int lane) {
        return graphX + (lane * laneSpacing);
    }

    private int rowCenterY(int rowStartY, int rowIndex) {
        return rowStartY + (rowIndex * ROW_HEIGHT) + (ROW_HEIGHT / 2);
    }

    private int laneColor(int lane) {
        return LANE_COLORS[Math.floorMod(lane, LANE_COLORS.length)];
    }

    private int fade(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private String trim(Font font, String value, int maxWidth) {
        String safeValue = ProjectUiSupport.safeText(value);
        if (font.width(safeValue) <= maxWidth) {
            return safeValue;
        }
        return font.plainSubstrByWidth(safeValue, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private Map<String, ProjectVariant> indexVariants(List<ProjectVariant> variants) {
        Map<String, ProjectVariant> indexed = new LinkedHashMap<>();
        if (variants == null) {
            return indexed;
        }
        for (ProjectVariant variant : variants) {
            indexed.put(variant.id(), variant);
        }
        return indexed;
    }

    private Map<Integer, List<ProjectVariant>> indexHeadVariants(
            List<CommitGraphNode> nodes,
            Map<String, ProjectVariant> variants
    ) {
        Map<Integer, List<ProjectVariant>> indexed = new LinkedHashMap<>();
        for (CommitGraphNode node : nodes) {
            List<ProjectVariant> laneVariants = new ArrayList<>();
            for (String variantId : node.headVariants()) {
                ProjectVariant variant = variants.get(variantId);
                if (variant != null) {
                    laneVariants.add(variant);
                }
            }
            if (!laneVariants.isEmpty()) {
                laneVariants.sort(Comparator
                        .comparing((ProjectVariant variant) -> !variant.main())
                        .thenComparing(ProjectVariant::createdAt)
                        .thenComparing(ProjectVariant::id));
                indexed.computeIfAbsent(node.lane(), ignored -> new ArrayList<>()).addAll(laneVariants);
            }
        }
        return indexed;
    }
}
