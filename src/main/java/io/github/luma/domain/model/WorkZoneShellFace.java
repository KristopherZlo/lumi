package io.github.luma.domain.model;

public record WorkZoneShellFace(
        Side side,
        int plane,
        int minA,
        int maxA,
        int minB,
        int maxB
) {

    public WorkZoneShellFace {
        side = side == null ? Side.UP : side;
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
