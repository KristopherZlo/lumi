package io.github.lumi.client;

import io.github.lumi.domain.model.BlockChange;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Camera-independent exact faces and unique same-color edges, grouped for bounded uploads. */
final class CompareOverlayGeometry {
    private static final Direction[] SIDES = Direction.values();

    List<Section> prepare(List<BlockChange> changes, BooleanSupplier cancelled) {
        var occupied = new Long2ByteOpenHashMap(changes.size());
        for (BlockChange block : changes) {
            checkCancelled(cancelled);
            occupied.put(position(block), (byte) (block.kind().ordinal() + 1));
        }
        var sections = new Long2ObjectLinkedOpenHashMap<List<Block>>();
        for (BlockChange block : changes) {
            checkCancelled(cancelled);
            int faces = 0;
            for (Direction side : SIDES) {
                if (!occupied.containsKey(BlockPos.asLong(
                        block.x() + side.getStepX(), block.y() + side.getStepY(),
                        block.z() + side.getStepZ()))) faces |= 1 << side.ordinal();
            }
            int edges = 0;
            for (int axis = 0; axis < 3; axis++) {
                for (int corner = 0; corner < 4; corner++) {
                    if (ownsEdge(block, occupied, axis, corner)) {
                        edges |= 1 << (axis * 4 + corner);
                    }
                }
            }
            long section = BlockPos.asLong(block.x() >> 4, block.y() >> 4, block.z() >> 4);
            sections.computeIfAbsent(section, ignored -> new ArrayList<>())
                    .add(new Block(block, faces, edges));
        }
        List<Section> prepared = new ArrayList<>(sections.size());
        sections.forEach((key, blocks) -> {
            int x = BlockPos.getX(key) << 4;
            int y = BlockPos.getY(key) << 4;
            int z = BlockPos.getZ(key) << 4;
            prepared.add(new Section(x, y, z, List.copyOf(blocks),
                    new AABB(x, y, z, x + 16, y + 16, z + 16),
                    blocks.stream().mapToInt(b -> Integer.bitCount(b.faces())).sum(),
                    blocks.stream().mapToInt(b -> Integer.bitCount(b.edges())).sum()));
        });
        return List.copyOf(prepared);
    }

    private boolean ownsEdge(
            BlockChange block, Long2ByteOpenHashMap occupied, int axis, int corner) {
        int first = (axis + 1) % 3;
        int second = (axis + 2) % 3;
        int firstStep = (corner & 1) == 0 ? -1 : 1;
        int secondStep = (corner & 2) == 0 ? -1 : 1;
        for (int neighbor = 1; neighbor <= 3; neighbor++) {
            int dx = offset(0, first, second, firstStep, secondStep, neighbor);
            int dy = offset(1, first, second, firstStep, secondStep, neighbor);
            int dz = offset(2, first, second, firstStep, secondStep, neighbor);
            // Only a preceding block of the same color owns an overlapping edge.
            if ((dx < 0 || (dx == 0 && (dy < 0 || (dy == 0 && dz < 0))))
                    && occupied.get(BlockPos.asLong(block.x() + dx,
                            block.y() + dy, block.z() + dz)) == block.kind().ordinal() + 1) {
                return false;
            }
        }
        return true;
    }

    private static int offset(
            int axis, int first, int second, int firstStep, int secondStep, int neighbor) {
        return (axis == first && (neighbor & 1) != 0 ? firstStep : 0)
                + (axis == second && (neighbor & 2) != 0 ? secondStep : 0);
    }

    private static long position(BlockChange block) {
        return BlockPos.asLong(block.x(), block.y(), block.z());
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
    }

    record Block(BlockChange change, int faces, int edges) { }

    record Section(int x, int y, int z, List<Block> blocks, AABB bounds,
            int faceCount, int edgeCount) { }
}
