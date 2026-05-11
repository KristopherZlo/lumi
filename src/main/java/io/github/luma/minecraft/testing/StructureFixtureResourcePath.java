package io.github.luma.minecraft.testing;

import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Maps bundled structure fixture resources to structure-template identifiers.
 */
record StructureFixtureResourcePath(String name, Identifier structureId) {

    static final String RESOURCE_DIRECTORY = "structure/testing";

    private static final String STRUCTURE_RESOURCE_PREFIX = "structure/";
    private static final String STRUCTURE_RESOURCE_SUFFIX = ".nbt";
    private static final String TESTING_STRUCTURE_PREFIX = "testing/";

    static boolean isFixtureResource(Identifier resourceId, String expectedNamespace) {
        return fromResourceId(resourceId, expectedNamespace).isPresent();
    }

    static Optional<StructureFixtureResourcePath> fromResourceId(
            Identifier resourceId,
            String expectedNamespace
    ) {
        if (resourceId == null || expectedNamespace == null
                || !expectedNamespace.equals(resourceId.getNamespace())) {
            return Optional.empty();
        }

        String path = resourceId.getPath();
        if (!path.startsWith(STRUCTURE_RESOURCE_PREFIX) || !path.endsWith(STRUCTURE_RESOURCE_SUFFIX)) {
            return Optional.empty();
        }

        String structurePath = path.substring(
                STRUCTURE_RESOURCE_PREFIX.length(),
                path.length() - STRUCTURE_RESOURCE_SUFFIX.length());
        if (!structurePath.startsWith(TESTING_STRUCTURE_PREFIX)) {
            return Optional.empty();
        }

        String name = structurePath.substring(TESTING_STRUCTURE_PREFIX.length());
        if (name.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new StructureFixtureResourcePath(
                name,
                Identifier.fromNamespaceAndPath(resourceId.getNamespace(), structurePath)
        ));
    }
}
