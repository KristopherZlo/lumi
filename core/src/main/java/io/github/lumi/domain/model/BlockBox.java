package io.github.lumi.domain.model;

import java.util.HashSet;
import java.util.Set;

/** Inclusive block-coordinate box used by partial and outside Restore. */
public record BlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public BlockBox {
        int lowX = Math.min(minX, maxX);
        int lowY = Math.min(minY, maxY);
        int lowZ = Math.min(minZ, maxZ);
        maxX = Math.max(minX, maxX);
        maxY = Math.max(minY, maxY);
        maxZ = Math.max(minZ, maxZ);
        minX = lowX;
        minY = lowY;
        minZ = lowZ;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean intersects(SectionKey section) {
        long x = (long) section.chunkX() * 16;
        long y = (long) section.sectionY() * 16;
        long z = (long) section.chunkZ() * 16;
        return maxX >= x && minX <= x + 15
                && maxY >= y && minY <= y + 15
                && maxZ >= z && minZ <= z + 15;
    }

    public boolean intersects(EntityChunkKey chunk) {
        long x = (long) chunk.chunkX() * 16;
        long z = (long) chunk.chunkZ() * 16;
        return maxX >= x && minX <= x + 15
                && maxZ >= z && minZ <= z + 15;
    }

    public boolean contains(SectionKey section) {
        long x = (long) section.chunkX() * 16;
        long y = (long) section.sectionY() * 16;
        long z = (long) section.chunkZ() * 16;
        return minX <= x && maxX >= x + 15
                && minY <= y && maxY >= y + 15
                && minZ <= z && maxZ >= z + 15;
    }

    public Set<SectionKey> sectionCells(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("Maximum section count must be positive");
        }
        int minChunkX = Math.floorDiv(minX, 16);
        int minSectionY = Math.floorDiv(minY, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int maxSectionY = Math.floorDiv(maxY, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        long count = Math.multiplyExact(
                Math.multiplyExact((long) maxChunkX - minChunkX + 1,
                        (long) maxSectionY - minSectionY + 1),
                (long) maxChunkZ - minChunkZ + 1);
        if (count > maximum) {
            throw new IllegalArgumentException("Selection contains too many section cells");
        }
        Set<SectionKey> cells = new HashSet<>((int) count);
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int y = minSectionY; y <= maxSectionY; y++) {
                for (int z = minChunkZ; z <= maxChunkZ; z++) {
                    cells.add(new SectionKey(x, y, z));
                }
            }
        }
        return Set.copyOf(cells);
    }
}
