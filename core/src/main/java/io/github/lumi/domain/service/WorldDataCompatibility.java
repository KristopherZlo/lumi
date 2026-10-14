package io.github.lumi.domain.service;

import io.github.lumi.domain.model.WorldDataVersion;
import java.io.IOException;
import java.util.Set;

/** Explicit forward routes; a higher integer alone does not authorize conversion. */
public final class WorldDataCompatibility {
    private static final Set<Integer> KNOWN = Set.of(4671, 4786, 4788, 4790, 4903, 5023);

    private WorldDataCompatibility() { }

    public static void requireForward(WorldDataVersion source, WorldDataVersion target) throws IOException {
        if (!KNOWN.contains(source.value()) || !KNOWN.contains(target.value())) {
            throw new IOException("Unsupported world data version: " + source.value() + " -> " + target.value());
        }
        if (source.value() > target.value()) {
            throw new IOException("History downgrade is unsupported: " + source.value() + " -> " + target.value());
        }
    }

    public static void requireCurrent(WorldDataVersion source, WorldDataVersion current) throws IOException {
        requireForward(source, current);
        if (!source.equals(current)) {
            throw new IOException("History conversion is required: " + source.value() + " -> " + current.value());
        }
    }
}
