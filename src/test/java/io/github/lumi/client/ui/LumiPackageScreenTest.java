package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiPackageScreenTest {
    @Test
    void exposesLegacyLocalPackageControls() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiPackageScreen.java"));

        assertTrue(source.contains("luma.action.open_packages_folder"));
        assertTrue(source.contains("luma.share.package_files_empty"));
        assertTrue(source.contains("browser.local(index).name().value()"));
        assertTrue(source.contains("PackageScreenController.Action.INSPECT"));
        assertTrue(source.contains("luma.action.combine_with_build"));
        assertTrue(source.contains("luma.action.delete_package"));
        assertTrue(source.contains("\"join\""));
        assertTrue(source.contains("\"branch\""));
        assertTrue(source.contains("\"trash\""));
        assertTrue(source.contains("ClientContextualHelpHint.IMPORT_EXPORT"));
        assertTrue(source.contains("contentOffset"));
    }
}
