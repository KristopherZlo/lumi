package io.github.lumi.buildconfig;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** One compiled adapter and the game releases declared by its artifact. */
public record MinecraftBuildTarget(
        String id, String minecraftVersion, List<String> minecraftVersions,
        int javaVersion, String loaderVersion, String fabricApiVersion,
        String worldeditVersionId, String axiomVersionId,
        Optional<String> worldeditCuiCoordinate, List<String> axiomRuntimeJars) {
    public MinecraftBuildTarget {
        for (String value : List.of(id, minecraftVersion, loaderVersion,
                fabricApiVersion, worldeditVersionId, axiomVersionId)) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Minecraft target fields must not be blank");
            }
        }
        if (!id.matches("[a-z][a-z0-9-]*") || javaVersion < 21) {
            throw new IllegalArgumentException("Invalid Minecraft target id or Java version");
        }
        minecraftVersions = List.copyOf(minecraftVersions);
        if (!minecraftVersion.matches("\\d+\\.\\d+(?:\\.\\d+)?")
                || minecraftVersions.isEmpty() || minecraftVersions.size() > 32
                || minecraftVersions.stream().anyMatch(version ->
                        !version.matches("\\d+\\.\\d+(?:\\.\\d+)?"))
                || new HashSet<>(minecraftVersions).size() != minecraftVersions.size()) {
            throw new IllegalArgumentException("Invalid Minecraft release list for " + id);
        }
        worldeditCuiCoordinate = worldeditCuiCoordinate.filter(value -> !value.isBlank());
        axiomRuntimeJars = List.copyOf(axiomRuntimeJars);
        if (axiomRuntimeJars.isEmpty()
                || new HashSet<>(axiomRuntimeJars).size() != axiomRuntimeJars.size()
                || axiomRuntimeJars.stream().anyMatch(name ->
                        !name.matches("[A-Za-z0-9._+-]+\\.jar"))) {
            throw new IllegalArgumentException("Invalid Axiom runtime jar list for " + id);
        }
    }

    public java.util.Map<String, Object> parameters() {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("id", id);
        values.put("minecraftVersion", minecraftVersion);
        values.put("minecraftVersions", minecraftVersions);
        values.put("javaVersion", javaVersion);
        values.put("loaderVersion", loaderVersion);
        values.put("fabricApiVersion", fabricApiVersion);
        values.put("worldeditVersionId", worldeditVersionId);
        values.put("axiomVersionId", axiomVersionId);
        values.put("worldeditCuiCoordinate", worldeditCuiCoordinate.orElse(null));
        values.put("axiomRuntimeJars", axiomRuntimeJars);
        return java.util.Collections.unmodifiableMap(values);
    }

}
