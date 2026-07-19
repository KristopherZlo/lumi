package io.github.lumi.network;

import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;
import java.util.Optional;

/** Optional wooden-sword bounds for modal-free Quick Rollback. */
public record QuickRollbackArgument(Optional<BlockBox> selection) {
    private static final String WHOLE_SCOPE = "whole";

    public QuickRollbackArgument {
        selection = Objects.requireNonNull(selection, "selection");
    }

    public String encode() {
        if (selection.isEmpty()) {
            return WHOLE_SCOPE;
        }
        BlockBox area = selection.orElseThrow();
        return area.minX() + "," + area.minY() + "," + area.minZ()
                + "," + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }

    public static QuickRollbackArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (WHOLE_SCOPE.equals(encoded)) {
            return new QuickRollbackArgument(Optional.empty());
        }
        String[] coordinates = encoded.split(",", -1);
        if (coordinates.length != 6) {
            throw new IllegalArgumentException("Invalid Quick Rollback selection");
        }
        try {
            return new QuickRollbackArgument(Optional.of(new BlockBox(
                    Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]),
                    Integer.parseInt(coordinates[3]),
                    Integer.parseInt(coordinates[4]),
                    Integer.parseInt(coordinates[5]))));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "Invalid Quick Rollback selection", invalid);
        }
    }
}
