package io.github.luma.ui.graph;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.ProjectUiSupport;
import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.Font;
import org.lwjgl.glfw.GLFW;

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
    private static final int ROW_HOVER = 0x223F5362;
    private static final int TEXT_PRIMARY = 0xFFF3F7FA;
    private static final int TEXT_MUTED = 0xFF98A6B3;
    private static final int TEXT_BADGE = 0xFF0B1016;

    private final List<CommitGraphNode> nodes;
    private final Map<String, ProjectVariant> variantById;
    private final Map<Integer, List<ProjectVariant>> headVariantsByLane;
    private final int laneCount;
    private final int preferredHeight;
    private final Consumer<String> openVersionDetails;

    public CommitGraphComponent(List<CommitGraphNode> nodes, List<ProjectVariant> variants) {
        this(nodes, variants, null);
    }

    public CommitGraphComponent(
            List<CommitGraphNode> nodes,
            List<ProjectVariant> variants,
            Consumer<String> openVersionDetails
    ) {
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.variantById = this.indexVariants(variants);
        this.headVariantsByLane = this.indexHeadVariants(this.nodes, this.variantById);
        this.laneCount = this.nodes.stream()
                .mapToInt(CommitGraphNode::laneCount)
                .max()
                .orElse(1);
        this.preferredHeight = CommitGraphGeometry.preferredHeight(this.nodes.size(), !this.headVariantsByLane.isEmpty());
        this.openVersionDetails = openVersionDetails;
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
    public void update(float delta, int mouseX, int mouseY) {
        super.update(delta, mouseX, mouseY);
        this.cursorStyle(this.openVersionDetails != null && this.geometry().nodeAt(mouseX, mouseY).isPresent()
                ? CursorStyle.HAND
                : CursorStyle.NONE);
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        if (this.openVersionDetails == null) {
            return super.onMouseDown(click, doubled);
        }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.onMouseDown(click, doubled);
        }

        Optional<CommitGraphNode> target = this.geometry().nodeAtLocal(click.x(), click.y());
        if (target.isEmpty()) {
            return super.onMouseDown(click, doubled);
        }

        this.openVersionDetails.accept(target.get().version().id());
        return true;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, BACKDROP);
        graphics.drawRectOutline(this.x, this.y, this.width, this.height, BORDER);
        if (this.nodes.isEmpty()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        CommitGraphGeometry geometry = this.geometry();
        CommitGraphNode hoveredNode = geometry.nodeAt(mouseX, mouseY).orElse(null);

        this.drawLegend(graphics, font, geometry.graphX(), this.y + CommitGraphGeometry.TOP_PADDING, geometry.textRight());
        this.drawLaneRuns(graphics, geometry);
        this.drawParentConnectors(graphics, geometry);
        this.drawRows(graphics, font, geometry, hoveredNode);
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

    private void drawParentConnectors(OwoUIGraphics graphics, CommitGraphGeometry geometry) {
        for (CommitGraphNode node : this.nodes) {
            for (CommitGraphGeometry.ConnectorSegment segment : geometry.parentConnectorSegments(node)) {
                graphics.drawLine(
                        segment.x1(),
                        segment.y1(),
                        segment.x2(),
                        segment.y2(),
                        2.0D,
                        Color.ofArgb(this.fade(this.laneColor(node.lane()), 0xCC))
                );
            }
        }
    }

    private void drawLaneRuns(OwoUIGraphics graphics, CommitGraphGeometry geometry) {
        for (CommitGraphGeometry.LaneRun run : geometry.laneRuns()) {
            graphics.drawLine(
                    run.x(),
                    run.y1(),
                    run.x(),
                    run.y2(),
                    2.0D,
                    Color.ofArgb(this.fade(this.laneColor(run.lane()), 0x88))
            );
        }
    }

    private void drawRows(OwoUIGraphics graphics, Font font, CommitGraphGeometry geometry, CommitGraphNode hoveredNode) {
        String hoveredVersionId = hoveredNode == null ? "" : hoveredNode.version().id();
        int textX = geometry.textX();
        int textRight = geometry.textRight();
        for (CommitGraphNode node : this.nodes) {
            boolean hovered = node.version().id().equals(hoveredVersionId);
            int rowY = geometry.rowCenterY(node);
            int laneColor = this.laneColor(node.lane());
            int nodeX = geometry.laneX(node.lane());

            if (hovered) {
                int rowTop = geometry.rowTopY(node) + 2;
                graphics.fill(this.x + 1, rowTop, this.x + this.width - 1, rowTop + CommitGraphGeometry.ROW_HEIGHT - 4, ROW_HOVER);
            }

            this.drawNode(graphics, nodeX, rowY, laneColor, node.activeHead(), hovered);
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

    private void drawNode(OwoUIGraphics graphics, int x, int y, int laneColor, boolean activeHead, boolean hovered) {
        graphics.drawCircle(x, y, 24, activeHead || hovered ? 6.8D : 5.0D, Color.ofArgb(0xFF091018));
        if (activeHead || hovered) {
            graphics.drawRing(
                    x,
                    y,
                    24,
                    hovered ? 5.2D : 5.0D,
                    activeHead ? 7.2D : 6.8D,
                    Color.ofArgb(laneColor),
                    Color.ofArgb(hovered ? 0xFFFFFFFF : 0xFFEAF5FF)
            );
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

    private CommitGraphGeometry geometry() {
        return new CommitGraphGeometry(
                this.x,
                this.y,
                this.width,
                this.laneCount,
                !this.headVariantsByLane.isEmpty(),
                this.nodes
        );
    }
}
