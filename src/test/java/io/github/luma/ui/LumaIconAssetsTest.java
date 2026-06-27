package io.github.luma.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LumaIconAssetsTest {

    private static final Path ICON_DIR = Path.of("src/main/resources/assets/lumi/textures/gui/icons");
    private static final int ICON_SIZE = 24;

    @Test
    void iconsStayPixelFriendlyAt24Pixels() throws IOException {
        List<Path> icons = this.iconPngs();

        Assertions.assertFalse(icons.isEmpty(), "No icon PNGs found in " + ICON_DIR);
        for (Path icon : icons) {
            BufferedImage image = ImageIO.read(icon.toFile());
            Assertions.assertNotNull(image, "Icon is not a readable PNG: " + icon);
            Assertions.assertEquals(ICON_SIZE, image.getWidth(), icon + " width");
            Assertions.assertEquals(ICON_SIZE, image.getHeight(), icon + " height");
        }
    }

    @Test
    void enabledIconsHaveDisabledStateTextures() throws IOException {
        List<Path> enabledIcons = this.iconPngs().stream()
                .filter(path -> !path.getFileName().toString().endsWith("_disabled.png"))
                .toList();

        for (Path icon : enabledIcons) {
            String fileName = icon.getFileName().toString();
            String disabledFileName = fileName.replace(".png", "_disabled.png");
            Assertions.assertTrue(
                    Files.exists(ICON_DIR.resolve(disabledFileName)),
                    "Missing disabled icon texture for " + fileName
            );
        }
    }

    @Test
    void iconButtonsKeepVisibleLabelEmpty() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/LumaUi.java"));

        Assertions.assertTrue(source.contains("styledButton(Component.empty()"));
        Assertions.assertFalse(source.contains("styledButton(tooltip"));
    }

    @Test
    void iconButtonsUseCompactLayoutWithNativeTextureRegion() throws IOException {
        String uiSource = Files.readString(Path.of("src/client/java/io/github/luma/ui/LumaUi.java"));
        String rendererSource = Files.readString(Path.of("src/client/java/io/github/luma/ui/IconButtonRenderer.java"));

        Assertions.assertTrue(uiSource.contains("private static final int ICON_BUTTON_HEIGHT = 18;"));
        Assertions.assertTrue(rendererSource.contains("private static final int TEXTURE_SIZE = 24;"));
        Assertions.assertTrue(rendererSource.contains("private static final int DRAW_SIZE = 16;"));
    }

    private List<Path> iconPngs() throws IOException {
        try (var paths = Files.list(ICON_DIR)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .toList();
        }
    }
}
