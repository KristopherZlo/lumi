package io.github.luma.ui.screen.section;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScreenSectionsZoneColorTest {

    @Test
    void saveCardsReceiveZoneColorFromHomeState() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectScreenSections.java"));

        assertTrue(source.contains("model.state().zoneColor(entry.version())"));
    }

    @Test
    void graphReceivesZoneColorsFromHomeState() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectScreenSections.java"));

        assertTrue(source.contains("model.state().zoneColorByVersionId()"));
    }
}
