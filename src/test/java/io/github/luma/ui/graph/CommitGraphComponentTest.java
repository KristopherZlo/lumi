package io.github.luma.ui.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitGraphComponentTest {

    @Test
    void rowsDrawZoneColorBeforeCommitTitle() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/graph/CommitGraphComponent.java"));
        String methodBody = methodBody(
                source,
                "    private void drawRows(OwoUIGraphics graphics, Font font, CommitGraphGeometry geometry, CommitGraphNode hoveredNode) {",
                "    private void drawNode(OwoUIGraphics graphics, int x, int y, int laneColor, boolean activeHead, boolean hovered) {"
        );

        assertTrue(methodBody.contains("this.zoneColorByVersionId.get(version.id())"));
        assertTrue(methodBody.contains("titleX"));
    }

    private static String methodBody(String source, String start, String end) {
        int methodIndex = source.indexOf(start);
        int nextMethodIndex = source.indexOf(end, methodIndex);

        assertTrue(methodIndex >= 0, "CommitGraphComponent should keep " + start.trim());
        assertTrue(nextMethodIndex > methodIndex, "The method should be bounded by " + end.trim());

        return source.substring(methodIndex, nextMethodIndex);
    }
}
