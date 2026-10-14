package io.github.lumi.buildconfig;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated build targets with one owner for each declared game release. */
public final class MinecraftTargetCatalog {
    private final String defaultTarget;
    private final Map<String, MinecraftBuildTarget> targets;

    public MinecraftTargetCatalog(String defaultTarget, List<MinecraftBuildTarget> entries) {
        var indexed = new LinkedHashMap<String, MinecraftBuildTarget>();
        var releases = new HashSet<String>();
        for (MinecraftBuildTarget target : entries) {
            if (indexed.putIfAbsent(target.id(), target) != null) {
                throw new IllegalArgumentException("Duplicate Minecraft target: " + target.id());
            }
            for (String release : target.minecraftVersions()) {
                if (!releases.add(release)) {
                    throw new IllegalArgumentException("Minecraft release has multiple targets: " + release);
                }
            }
        }
        targets = Map.copyOf(indexed);
        this.defaultTarget = defaultTarget;
        select(defaultTarget);
    }

    public MinecraftBuildTarget select(String id) {
        MinecraftBuildTarget target = targets.get(id);
        if (target == null) {
            throw new IllegalArgumentException("Unknown Minecraft target: " + id);
        }
        return target;
    }

    public MinecraftBuildTarget defaultTarget() { return select(defaultTarget); }
    public List<MinecraftBuildTarget> targets() {
        return targets.values().stream().sorted(java.util.Comparator.comparing(
                MinecraftBuildTarget::id)).toList();
    }
}
