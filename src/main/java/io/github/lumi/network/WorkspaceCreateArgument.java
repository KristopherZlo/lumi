package io.github.lumi.network;

import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;
import java.util.Optional;

/** Canonical name and optional selection used to create a workspace. */
public record WorkspaceCreateArgument(String name, Optional<BlockBox> bounds) {
    private static final int MAX_NAME_LENGTH = 256;
    private static final String WHOLE_DIMENSION = "whole";

    public WorkspaceCreateArgument {
        Objects.requireNonNull(name, "name");
        bounds = Objects.requireNonNull(bounds, "bounds");
        name = name.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid workspace name");
        }
    }

    public String encode() {
        if (bounds.isEmpty()) {
            return name + "\n" + WHOLE_DIMENSION;
        }
        BlockBox area = bounds.orElseThrow();
        return name + "\n" + area.minX() + "," + area.minY() + "," + area.minZ()
                + "," + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }

    public static WorkspaceCreateArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid workspace create argument");
        }
        String name = encoded.substring(0, separator);
        String selection = encoded.substring(separator + 1);
        if (selection.equals(WHOLE_DIMENSION)) {
            return new WorkspaceCreateArgument(name, Optional.empty());
        }
        String[] coordinates = selection.split(",", -1);
        if (coordinates.length != 6) {
            throw new IllegalArgumentException("Invalid workspace selection");
        }
        try {
            return new WorkspaceCreateArgument(name, Optional.of(new BlockBox(
                    Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]), Integer.parseInt(coordinates[3]),
                    Integer.parseInt(coordinates[4]), Integer.parseInt(coordinates[5]))));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid workspace selection", invalid);
        }
    }
}
