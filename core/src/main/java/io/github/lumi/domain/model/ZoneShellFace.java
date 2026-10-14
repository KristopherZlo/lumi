package io.github.lumi.domain.model;

import java.util.Objects;

/** One merged exposed rectangle of a zone's 16-cubed cell shell. */
public record ZoneShellFace(
        Side side,
        int plane,
        int minA,
        int maxA,
        int minB,
        int maxB) {
    public ZoneShellFace {
        Objects.requireNonNull(side, "side");
        if (minA >= maxA || minB >= maxB) {
            throw new IllegalArgumentException(
                    "Zone shell face must have positive area");
        }
    }

    public enum Side {
        WEST,
        EAST,
        DOWN,
        UP,
        NORTH,
        SOUTH
    }
}
