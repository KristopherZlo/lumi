package io.github.luma.gbreak.server;

import io.github.luma.gbreak.bug.GameBreakingBug;
import io.github.luma.gbreak.state.BugStateController;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;

final class CorruptedBlocksService {

    private static final int HORIZONTAL_RADIUS = 32;
    private static final int VERTICAL_RADIUS = 3;
    private static final int MAX_ATTEMPTS_PER_TICK = 2048;
    private static final int MAX_REPLACEMENT_ATTEMPTS = 48;
    private static final Set<Block> UNSAFE_BLOCKS = Set.of(
            Blocks.AIR,
            Blocks.CAVE_AIR,
            Blocks.VOID_AIR,
            Blocks.WATER,
            Blocks.LAVA,
            Blocks.MOVING_PISTON,
            Blocks.PISTON_HEAD,
            Blocks.END_GATEWAY,
            Blocks.END_PORTAL,
            Blocks.NETHER_PORTAL,
            Blocks.FIRE,
            Blocks.SOUL_FIRE,
            Blocks.LIGHT,
            Blocks.STRUCTURE_BLOCK,
            Blocks.JIGSAW,
            Blocks.COMMAND_BLOCK,
            Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK
    );
    private static final List<BlockPos> SEARCH_OFFSETS = buildSearchOffsets();
    private static final BlockState FALLBACK_REPLACEMENT = Blocks.STONE.getDefaultState();

    private final BugStateController bugState = BugStateController.getInstance();

    void tick(MinecraftServer server) {
        if (!this.bugState.isActive(GameBreakingBug.CORRUPTED_BLOCKS)) {
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayerList().stream()
                .filter(candidate -> !candidate.isSpectator())
                .findFirst()
                .orElse(null);
        if (player == null) {
            return;
        }

        ServerWorld world = player.getEntityWorld();
        BlockPos origin = player.getBlockPos();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int startIndex = random.nextInt(SEARCH_OFFSETS.size());
        for (int attempt = 0; attempt < Math.min(MAX_ATTEMPTS_PER_TICK, SEARCH_OFFSETS.size()); attempt++) {
            BlockPos offset = SEARCH_OFFSETS.get((startIndex + attempt) % SEARCH_OFFSETS.size());
            BlockPos candidatePos = origin.add(offset);
            BlockState currentState = world.getBlockState(candidatePos);
            if (!this.isCandidate(player, world, candidatePos, currentState)) {
                continue;
            }

            BlockState replacement = this.randomReplacement(world.getRandom(), currentState);
            if (replacement == null) {
                return;
            }

            world.setBlockState(candidatePos, replacement, Block.NOTIFY_ALL);
            return;
        }
    }

    private boolean isCandidate(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        if (state.isAir() || state.hasBlockEntity()) {
            return false;
        }
        if (!this.isExposed(world, pos)) {
            return false;
        }
        return this.isVisible(player, world, pos);
    }

    private boolean isExposed(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.offset(direction);
            BlockState neighborState = world.getBlockState(neighborPos);
            if (neighborState.isAir() || neighborState.getCollisionShape(world, neighborPos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isVisible(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        Vec3d eyePos = player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        HitResult hitResult = world.raycast(new RaycastContext(
                eyePos,
                target,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));
        return hitResult.getType() == HitResult.Type.BLOCK
                && ((BlockHitResult) hitResult).getBlockPos().equals(pos);
    }

    private BlockState randomReplacement(Random registryRandom, BlockState currentState) {
        ThreadLocalRandom stateRandom = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_REPLACEMENT_ATTEMPTS; attempt++) {
            BlockState replacement = Registries.BLOCK.getRandom(registryRandom)
                    .map(entry -> this.randomizeState(entry.value().getDefaultState(), stateRandom))
                    .orElse(FALLBACK_REPLACEMENT);
            if (this.isUsableReplacement(replacement, currentState)) {
                return replacement;
            }
        }
        return this.isUsableReplacement(FALLBACK_REPLACEMENT, currentState) ? FALLBACK_REPLACEMENT : null;
    }

    private boolean isUsableReplacement(BlockState replacement, BlockState currentState) {
        return !replacement.isAir()
                && !UNSAFE_BLOCKS.contains(replacement.getBlock())
                && !replacement.isOf(currentState.getBlock());
    }

    private BlockState randomizeState(BlockState baseState, ThreadLocalRandom random) {
        BlockState randomized = baseState;
        for (Property<?> property : baseState.getProperties()) {
            randomized = this.randomizeProperty(randomized, property, random);
        }
        return randomized;
    }

    private BlockState randomizeProperty(BlockState state, Property<?> property, ThreadLocalRandom random) {
        return this.randomizeTypedProperty(state, property, random);
    }

    private <T extends Comparable<T>> BlockState randomizeTypedProperty(
            BlockState state,
            Property<T> property,
            ThreadLocalRandom random
    ) {
        List<T> values = List.copyOf(property.getValues());
        if (values.size() <= 1) {
            return state;
        }

        T value = values.get(random.nextInt(values.size()));
        return state.with(property, value);
    }

    private static List<BlockPos> buildSearchOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
            for (int x = -HORIZONTAL_RADIUS; x <= HORIZONTAL_RADIUS; x++) {
                for (int z = -HORIZONTAL_RADIUS; z <= HORIZONTAL_RADIUS; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    offsets.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(offsets);
    }

}
