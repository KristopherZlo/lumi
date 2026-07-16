package io.github.lumi.domain.model;

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
}
