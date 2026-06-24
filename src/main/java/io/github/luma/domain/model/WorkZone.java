package io.github.luma.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public record WorkZone(
        String id,
        String projectId,
        String name,
        int color,
        List<WorkZoneCell> cells,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {

    public WorkZone {
        id = id == null ? "" : id;
        projectId = projectId == null ? "" : projectId;
        name = name == null ? "" : name.trim();
        cells = sortedCells(cells);
        createdBy = createdBy == null || createdBy.isBlank() ? "player" : createdBy;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean contains(WorkZoneCell cell) {
        return this.cells.contains(cell);
    }

    public WorkZone withCell(WorkZoneCell cell, Instant now) {
        if (cell == null || this.contains(cell)) {
            return this;
        }
        List<WorkZoneCell> next = new ArrayList<>(this.cells);
        next.add(cell);
        return new WorkZone(
                this.id,
                this.projectId,
                this.name,
                this.color,
                next,
                this.createdBy,
                this.createdAt,
                now
        );
    }

    public WorkZone withName(String name, Instant now) {
        return new WorkZone(this.id, this.projectId, name, this.color, this.cells, this.createdBy, this.createdAt, now);
    }

    private static List<WorkZoneCell> sortedCells(List<WorkZoneCell> cells) {
        TreeSet<WorkZoneCell> sorted = new TreeSet<>();
        if (cells != null) {
            sorted.addAll(cells);
        }
        return List.copyOf(sorted);
    }
}
