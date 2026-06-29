package io.github.luma.ui.screen.section;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSaveCardViewTest {

    @Test
    void textShowsZoneColorBeforeCommitName() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectSaveCardView.java"));
        String methodBody = methodBody(
                source,
                "    private FlowLayout text(Model model, Sizing horizontalSizing) {",
                "    private FlowLayout actionRow(Model model) {"
        );

        assertTrue(methodBody.contains("this.zoneTitle(model)"));
        assertTrue(source.contains("private FlowLayout zoneTitle(Model model)"));
        assertTrue(source.contains("model.zoneColor()"));
    }

    @Test
    void saveCardsDoNotRenderLatestSaveTextTag() throws IOException {
        String card = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectSaveCardView.java"));
        String sections = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectScreenSections.java"));

        assertFalse(card.contains("luma.history.current_badge"));
        assertFalse(sections.contains("section.child(LumaUi.caption(Component.translatable(\"luma.history.current_badge\")))"));
    }

    private static String methodBody(String source, String start, String end) {
        int methodIndex = source.indexOf(start);
        int nextMethodIndex = source.indexOf(end, methodIndex);

        assertTrue(methodIndex >= 0, "ProjectSaveCardView should keep " + start.trim());
        assertTrue(nextMethodIndex > methodIndex, "The method should be bounded by " + end.trim());

        return source.substring(methodIndex, nextMethodIndex);
    }
}
