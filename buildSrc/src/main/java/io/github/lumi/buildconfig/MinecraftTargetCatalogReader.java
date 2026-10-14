package io.github.lumi.buildconfig;

import groovy.json.JsonSlurper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads the repository's build configuration without Minecraft dependencies. */
public final class MinecraftTargetCatalogReader {
    public MinecraftTargetCatalog read(Path path) throws IOException {
        try {
            Map<?, ?> root = object(new JsonSlurper().parseText(Files.readString(path)));
            if (!Integer.valueOf(1).equals(root.get("schema"))) {
                throw new IllegalArgumentException("Unsupported Minecraft target schema");
            }
            var targets = new ArrayList<MinecraftBuildTarget>();
            for (Object entry : list(root.get("targets"))) {
                Map<?, ?> target = object(entry);
                if (!(target.get("javaVersion") instanceof Integer javaVersion)) {
                    throw new IllegalArgumentException("javaVersion must be an integer");
                }
                targets.add(new MinecraftBuildTarget(
                        text(target, "id"), text(target, "minecraftVersion"),
                        strings(target, "minecraftVersions"), javaVersion,
                        text(target, "loaderVersion"), text(target, "fabricApiVersion"),
                        text(target, "worldeditVersionId"), text(target, "axiomVersionId"),
                        target.get("worldeditCuiCoordinate") == null ? Optional.empty()
                                : Optional.of(text(target, "worldeditCuiCoordinate")),
                        strings(target, "axiomRuntimeJars")));
            }
            return new MinecraftTargetCatalog(text(root, "defaultTarget"), targets);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Minecraft target catalog " + path
                    + ": " + invalid.getMessage(), invalid);
        }
    }

    private Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected a JSON object");
    }

    private List<?> list(Object value) {
        if (value instanceof List<?> values) return values;
        throw new IllegalArgumentException("Expected a JSON array");
    }

    private String text(Map<?, ?> value, String name) {
        if (value.get(name) instanceof String text && !text.isBlank()) return text;
        throw new IllegalArgumentException("Missing or invalid target field: " + name);
    }

    private List<String> strings(Map<?, ?> value, String name) {
        return list(value.get(name)).stream().map(entry -> {
            if (entry instanceof String text && !text.isBlank()) return text;
            throw new IllegalArgumentException("Invalid target list: " + name);
        }).toList();
    }
}
