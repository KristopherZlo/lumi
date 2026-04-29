package io.github.luma.ui.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pixel geometry for the rendered commit graph.
 */
final class CommitGraphGeometry {

    static final int ROW_HEIGHT = 32;
    static final int TOP_PADDING = 8;
    static final int BOTTOM_PADDING = 8;
    static final int SIDE_PADDING = 8;
    static final int LEGEND_HEIGHT = 20;
    static final int MIN_TEXT_WIDTH = 120;

    private final int x;
    private final int y;
    private final int width;
    private final int laneCount;
    private final boolean hasLegend;
    private final List<CommitGraphNode> nodes;

    CommitGraphGeometry(
            int x,
            int y,
            int width,
            int laneCount,
            boolean hasLegend,
            List<CommitGraphNode> nodes
    ) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.laneCount = Math.max(1, laneCount);
        this.hasLegend = hasLegend;
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    static int preferredHeight(int nodeCount, boolean hasLegend) {
        return TOP_PADDING
                + (hasLegend ? LEGEND_HEIGHT : 0)
                + Math.max(1, nodeCount) * ROW_HEIGHT
                + BOTTOM_PADDING;
    }

    int graphX() {
        return this.x + SIDE_PADDING + 4;
    }

    int textRight() {
        return this.x + this.width - SIDE_PADDING;
    }

    int laneSpacing() {
        if (this.laneCount <= 1) {
            return 0;
        }
        int available = Math.max(28, this.width - SIDE_PADDING - SIDE_PADDING - MIN_TEXT_WIDTH - 24);
        return Math.max(4, Math.min(16, available / Math.max(1, this.laneCount - 1)));
    }

    int textX() {
        int graphX = this.graphX();
        int graphWidth = Math.max(0, (this.laneCount - 1) * this.laneSpacing());
        return Math.min(
                graphX + graphWidth + 18,
                Math.max(graphX + 42, this.textRight() - MIN_TEXT_WIDTH)
        );
    }

    int rowStartY() {
        return this.y + TOP_PADDING + (this.hasLegend ? LEGEND_HEIGHT : 0);
    }

    int rowTopY(CommitGraphNode node) {
        return this.rowStartY() + (node.rowIndex() * ROW_HEIGHT);
    }

    int rowCenterY(CommitGraphNode node) {
        return this.rowCenterY(node.rowIndex());
    }

    int rowCenterY(int rowIndex) {
        return this.rowStartY() + (rowIndex * ROW_HEIGHT) + (ROW_HEIGHT / 2);
    }

    int laneX(int lane) {
        return this.graphX() + (lane * this.laneSpacing());
    }

    Optional<CommitGraphNode> nodeAt(double screenX, double screenY) {
        return this.nodeAtLocal(screenX - this.x, screenY - this.y);
    }

    Optional<CommitGraphNode> nodeAtLocal(double localX, double localY) {
        if (localX < 0 || localX >= this.width || this.nodes.isEmpty()) {
            return Optional.empty();
        }

        int rowStartLocalY = TOP_PADDING + (this.hasLegend ? LEGEND_HEIGHT : 0);
        if (localY < rowStartLocalY) {
            return Optional.empty();
        }

        int rowIndex = (int) ((localY - rowStartLocalY) / ROW_HEIGHT);
        if (rowIndex < 0 || rowIndex >= this.nodes.size()) {
            return Optional.empty();
        }
        return Optional.of(this.nodes.get(rowIndex));
    }

    List<LaneRun> laneRuns() {
        List<LaneRun> runs = new ArrayList<>();
        for (int lane = 0; lane < this.laneCount; lane++) {
            int previousRowIndex = -1;
            for (CommitGraphNode node : this.nodes) {
                if (!node.activeLanes().contains(lane)) {
                    continue;
                }
                if (previousRowIndex >= 0) {
                    runs.add(new LaneRun(
                            lane,
                            this.laneX(lane),
                            this.rowCenterY(previousRowIndex),
                            this.rowCenterY(node.rowIndex())
                    ));
                }
                previousRowIndex = node.rowIndex();
            }
        }
        return List.copyOf(runs);
    }

    List<ConnectorSegment> parentConnectorSegments(CommitGraphNode node) {
        if (node.parentLane() < 0 || node.parentRowIndex() < 0 || node.parentLane() == node.lane()) {
            return List.of();
        }

        int nodeX = this.laneX(node.lane());
        int nodeY = this.rowCenterY(node.rowIndex());
        int parentX = this.laneX(node.parentLane());
        int parentY = this.rowCenterY(node.parentRowIndex());
        int bendY = nodeY + ((parentY - nodeY) / 2);

        List<ConnectorSegment> segments = new ArrayList<>();
        segments.add(new ConnectorSegment(nodeX, nodeY, nodeX, bendY));
        segments.add(new ConnectorSegment(nodeX, bendY, parentX, bendY));
        segments.add(new ConnectorSegment(parentX, bendY, parentX, parentY));
        return List.copyOf(segments);
    }

    record LaneRun(int lane, int x, int y1, int y2) {
    }

    record ConnectorSegment(int x1, int y1, int x2, int y2) {

        boolean orthogonal() {
            return this.x1 == this.x2 || this.y1 == this.y2;
        }
    }
}
