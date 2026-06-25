package io.github.luma.domain.model;

public record WorkZoneCell(int x, int y, int z) implements Comparable<WorkZoneCell> {

    public static final int SIZE = 16;

    public static WorkZoneCell from(BlockPoint point) {
        return new WorkZoneCell(
                Math.floorDiv(point.x(), SIZE),
                Math.floorDiv(point.y(), SIZE),
                Math.floorDiv(point.z(), SIZE)
        );
    }

    @Override
    public int compareTo(WorkZoneCell other) {
        int byX = Integer.compare(this.x, other.x);
        if (byX != 0) {
            return byX;
        }
        int byY = Integer.compare(this.y, other.y);
        return byY != 0 ? byY : Integer.compare(this.z, other.z);
    }
}
