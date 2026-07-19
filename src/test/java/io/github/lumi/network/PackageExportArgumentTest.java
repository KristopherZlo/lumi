package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.PackageName;
import org.junit.jupiter.api.Test;

class PackageExportArgumentTest {
    @Test
    void roundTripsTheOptionalPreviewChoice() {
        var argument = new PackageExportArgument(
                new PackageName("clock-v2"), true);

        var decoded = PackageExportArgument.parse(argument.encode());

        assertEquals(new PackageName("clock-v2"), decoded.name());
        assertTrue(decoded.includePreview());
    }

    @Test
    void acceptsLegacyBarePackageNamesWithoutPreviews() {
        var decoded = PackageExportArgument.parse("clock-v1");

        assertEquals(new PackageName("clock-v1"), decoded.name());
        assertFalse(decoded.includePreview());
    }
}
