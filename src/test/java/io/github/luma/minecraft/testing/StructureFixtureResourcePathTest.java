package io.github.luma.minecraft.testing;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureFixtureResourcePathTest {

    @Test
    void mapsTestingStructureResourceToTemplateId() {
        StructureFixtureResourcePath path = StructureFixtureResourcePath.fromResourceId(
                Identifier.fromNamespaceAndPath("lumi", "structure/testing/door.nbt"),
                "lumi"
        ).orElseThrow();

        assertEquals("door", path.name());
        assertEquals(Identifier.fromNamespaceAndPath("lumi", "testing/door"), path.structureId());
    }

    @Test
    void keepsNestedFixtureNamesStable() {
        StructureFixtureResourcePath path = StructureFixtureResourcePath.fromResourceId(
                Identifier.fromNamespaceAndPath("lumi", "structure/testing/redstone/piston.nbt"),
                "lumi"
        ).orElseThrow();

        assertEquals("redstone/piston", path.name());
        assertEquals(Identifier.fromNamespaceAndPath("lumi", "testing/redstone/piston"), path.structureId());
    }

    @Test
    void rejectsResourcesOutsideTheFixtureCatalog() {
        assertFalse(StructureFixtureResourcePath.fromResourceId(
                Identifier.fromNamespaceAndPath("other", "structure/testing/main.nbt"),
                "lumi"
        ).isPresent());
        assertFalse(StructureFixtureResourcePath.fromResourceId(
                Identifier.fromNamespaceAndPath("lumi", "structure/production/main.nbt"),
                "lumi"
        ).isPresent());
        assertFalse(StructureFixtureResourcePath.fromResourceId(
                Identifier.fromNamespaceAndPath("lumi", "structure/testing/main.snbt"),
                "lumi"
        ).isPresent());
    }

    @Test
    void predicateMatchesOnlyFixtureResources() {
        assertTrue(StructureFixtureResourcePath.isFixtureResource(
                Identifier.fromNamespaceAndPath("lumi", "structure/testing/bud.nbt"),
                "lumi"
        ));
        assertFalse(StructureFixtureResourcePath.isFixtureResource(
                Identifier.fromNamespaceAndPath("lumi", "structure/testing.nbt"),
                "lumi"
        ));
    }
}
