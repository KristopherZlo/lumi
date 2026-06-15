package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.ComparatorMode;

/**
 * Programmatic redstone fixtures for the singleplayer structure runtime suite.
 */
final class GeneratedRedstoneStructureFixtures {

    private static final String DUST_LINE = "dust-line";
    private static final String TORCH_INVERTER = "torch-inverter";
    private static final String REPEATER_LOCK = "repeater-lock";
    private static final String COMPARATOR_MODE = "comparator-mode";
    private static final String OBSERVER_PULSE = "observer-pulse";
    private static final String DISPENSER_TRIGGER = "dispenser-trigger";
    private static final String OBSERVER_PISTON = "observer-piston";
    private static final String CLOSED_OBSERVER_PISTON = "closed-observer-piston";

    private static final List<String> NAMES = List.of(
            DUST_LINE,
            TORCH_INVERTER,
            REPEATER_LOCK,
            COMPARATOR_MODE,
            OBSERVER_PULSE,
            DISPENSER_TRIGGER,
            OBSERVER_PISTON,
            CLOSED_OBSERVER_PISTON
    );

    private GeneratedRedstoneStructureFixtures() {
    }

    static List<String> names() {
        return NAMES;
    }

    static boolean isGenerated(String name) {
        return NAMES.contains(name);
    }

    static List<StructureFixtureControl> controls(String name, SingleplayerTestVolume volume) {
        return List.of(switch (name) {
            case COMPARATOR_MODE -> control(volume, comparatorPos(volume), Direction.UP, "comparator mode toggle");
            case TORCH_INVERTER -> control(volume, torchInverterLeverPos(volume), Direction.NORTH, "torch inverter lever");
            case DISPENSER_TRIGGER -> control(volume, dispenserLeverPos(volume), Direction.UP, "dispenser trigger lever");
            case OBSERVER_PISTON, CLOSED_OBSERVER_PISTON ->
                    control(volume, observerPistonLeverPos(volume), Direction.UP, "observer sticky piston lever");
            case DUST_LINE, OBSERVER_PULSE ->
                    control(volume, floorLeverPos(volume), Direction.UP, name + " lever");
            case REPEATER_LOCK -> control(volume, repeaterLeverPos(volume), Direction.UP, "repeater lock lever");
            default -> throw new IllegalArgumentException("Unknown generated structure fixture " + name);
        });
    }

    static void load(String name, ServerLevel level, SingleplayerTestVolume volume) {
        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            switch (name) {
                case DUST_LINE -> loadDustLine(level, volume);
                case TORCH_INVERTER -> loadTorchInverter(level, volume);
                case REPEATER_LOCK -> loadRepeaterLock(level, volume);
                case COMPARATOR_MODE -> loadComparatorMode(level, volume);
                case OBSERVER_PULSE -> loadObserverPulse(level, volume);
                case DISPENSER_TRIGGER -> loadDispenserTrigger(level, volume);
                case OBSERVER_PISTON -> loadObserverPiston(level, volume);
                case CLOSED_OBSERVER_PISTON -> loadClosedObserverPiston(level, volume);
                default -> throw new IllegalArgumentException("Unknown generated structure fixture " + name);
            }
        });
    }

    static StructureFixtureSnapshot.ComparisonPolicy comparisonPolicy(
            String name,
            SingleplayerTestVolume volume
    ) {
        StructureFixtureSnapshot.ComparisonPolicy policy = StructureFixtureSnapshot.exactComparison();
        if (TORCH_INVERTER.equals(name)) {
            return policy.withRedstoneTorchLitAt(List.of(torchInverterBlockPos(volume).above()));
        }
        if (CLOSED_OBSERVER_PISTON.equals(name)) {
            return policy.withObserverPoweredAt(List.of(
                    closedObserverPistonObserverHomePos(volume),
                    closedObserverPistonPairedObserverPos(volume)
            ));
        }
        if (OBSERVER_PULSE.equals(name)) {
            return policy
                    .withObserverPoweredAt(List.of(observerPulseObserverPos(volume)))
                    .withRedstoneLampLitAt(List.of(observerPulseObserverPos(volume).east()));
        }
        return policy;
    }

    static void verifyUndoSmoke(
            String name,
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        switch (name) {
            case DUST_LINE -> verifyDustLine(level, volume, record);
            case TORCH_INVERTER -> verifyTorchInverter(level, volume, record);
            case REPEATER_LOCK -> verifyRepeaterLock(level, volume, record);
            case COMPARATOR_MODE -> verifyComparatorMode(level, volume, record);
            case OBSERVER_PULSE -> verifyObserverPulse(level, volume, record);
            case DISPENSER_TRIGGER -> verifyDispenserTrigger(level, volume, record);
            case OBSERVER_PISTON -> verifyObserverPiston(level, volume, record);
            case CLOSED_OBSERVER_PISTON -> verifyClosedObserverPiston(level, volume, record);
            default -> throw new IllegalArgumentException("Unknown generated structure fixture " + name);
        }
    }

    private static void loadDustLine(ServerLevel level, SingleplayerTestVolume volume) {
        placeFloorLever(level, floorLeverPos(volume), Direction.NORTH);
        for (int x = 3; x <= 5; x += 1) {
            placeSupport(level, pos(volume, x, 0, 2), Blocks.STONE);
            level.setBlock(pos(volume, x, 1, 2),
                    Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.POWER, 0), 3);
        }
        level.setBlock(pos(volume, 6, 1, 2), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
    }

    private static void loadTorchInverter(ServerLevel level, SingleplayerTestVolume volume) {
        BlockPos block = torchInverterBlockPos(volume);
        BlockPos lever = torchInverterLeverPos(volume);
        placeSupport(level, block, Blocks.BLUE_CONCRETE);
        level.setBlock(lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.WALL)
                .setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, false), 3);
        level.setBlock(block.above(), Blocks.REDSTONE_TORCH.defaultBlockState()
                .setValue(RedstoneTorchBlock.LIT, true), 3);
    }

    private static void loadRepeaterLock(ServerLevel level, SingleplayerTestVolume volume) {
        placeFloorLever(level, repeaterLeverPos(volume), Direction.NORTH);
        for (BlockPos wire : List.of(pos(volume, 3, 1, 7), pos(volume, 4, 1, 7))) {
            placeSupport(level, wire.below(), Blocks.STONE);
            level.setBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedStoneWireBlock.POWER, 0), 3);
        }
        BlockPos mainRepeater = repeaterMainPos(volume);
        BlockPos lockingRepeater = repeaterLockPos(volume);
        placeSupport(level, mainRepeater.below(), Blocks.STONE);
        placeSupport(level, lockingRepeater.below(), Blocks.STONE);
        level.setBlock(mainRepeater, Blocks.REPEATER.defaultBlockState()
                .setValue(DiodeBlock.FACING, Direction.EAST)
                .setValue(RepeaterBlock.LOCKED, false), 3);
        level.setBlock(lockingRepeater, Blocks.REPEATER.defaultBlockState()
                .setValue(DiodeBlock.FACING, Direction.NORTH)
                .setValue(RepeaterBlock.LOCKED, false), 3);
        level.setBlock(pos(volume, 5, 1, 5), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
    }

    private static void loadComparatorMode(ServerLevel level, SingleplayerTestVolume volume) {
        BlockPos comparator = comparatorPos(volume);
        placeSupport(level, comparator.below(), Blocks.BLUE_CONCRETE);
        level.setBlock(comparator, Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.FACING, Direction.EAST)
                .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE)
                .setValue(ComparatorBlock.POWERED, false), 3);
    }

    private static void loadObserverPulse(ServerLevel level, SingleplayerTestVolume volume) {
        placeFloorLever(level, floorLeverPos(volume), Direction.NORTH);
        BlockPos observer = observerPulseObserverPos(volume);
        level.setBlock(observer, Blocks.OBSERVER.defaultBlockState()
                .setValue(ObserverBlock.FACING, Direction.WEST), 3);
        level.setBlock(observer.east(), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
    }

    private static void loadDispenserTrigger(ServerLevel level, SingleplayerTestVolume volume) {
        BlockPos dispenser = dispenserPos(volume);
        level.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState()
                .setValue(DispenserBlock.FACING, Direction.NORTH)
                .setValue(DispenserBlock.TRIGGERED, false), 3);
        level.setBlock(dispenserLeverPos(volume), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, false), 3);
    }

    private static void loadObserverPiston(ServerLevel level, SingleplayerTestVolume volume) {
        placeFloorLever(level, observerPistonLeverPos(volume), Direction.NORTH);
        level.setBlock(observerPistonBasePos(volume), Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, false), 3);
        level.setBlock(observerPistonObserverHomePos(volume), Blocks.OBSERVER.defaultBlockState()
                .setValue(ObserverBlock.FACING, Direction.EAST), 3);
    }

    private static void loadClosedObserverPiston(ServerLevel level, SingleplayerTestVolume volume) {
        placeFloorLever(level, observerPistonLeverPos(volume), Direction.NORTH);
        level.setBlock(observerPistonBasePos(volume), Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.UP)
                .setValue(PistonBaseBlock.EXTENDED, false), 3);
        level.setBlock(closedObserverPistonObserverHomePos(volume), Blocks.OBSERVER.defaultBlockState()
                .setValue(ObserverBlock.FACING, Direction.EAST), 3);
        level.setBlock(closedObserverPistonPairedObserverPos(volume), Blocks.OBSERVER.defaultBlockState()
                .setValue(ObserverBlock.FACING, Direction.WEST), 3);
    }

    private static void verifyDustLine(
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        record.accept(countBlocks(level, volume, Blocks.REDSTONE_WIRE) == 3,
                DUST_LINE + " rollback kept the three-cell dust line");
        record.accept(!level.getBlockState(pos(volume, 6, 1, 2)).getValue(RedstoneLampBlock.LIT),
                DUST_LINE + " rollback left the lamp unpowered");
    }

    private static void verifyTorchInverter(
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        record.accept(level.getBlockState(torchInverterBlockPos(volume).above()).is(Blocks.REDSTONE_TORCH),
                TORCH_INVERTER + " rollback kept the redstone torch");
        record.accept(level.getBlockState(torchInverterBlockPos(volume).above()).getValue(RedstoneTorchBlock.LIT),
                TORCH_INVERTER + " rollback left the torch lit");
    }

    private static void verifyRepeaterLock(
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        record.accept(level.getBlockState(repeaterMainPos(volume)).is(Blocks.REPEATER),
                REPEATER_LOCK + " rollback kept the main repeater");
        record.accept(level.getBlockState(repeaterLockPos(volume)).is(Blocks.REPEATER),
                REPEATER_LOCK + " rollback kept the side locking repeater");
    }

    private static void verifyComparatorMode(
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        record.accept(level.getBlockState(comparatorPos(volume)).is(Blocks.COMPARATOR),
                COMPARATOR_MODE + " rollback kept the comparator");
        record.accept(level.getBlockState(comparatorPos(volume)).getValue(ComparatorBlock.MODE) == ComparatorMode.COMPARE,
                COMPARATOR_MODE + " rollback restored compare mode");
    }

    private static void verifyObserverPulse(
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        record.accept(level.getBlockState(observerPulseObserverPos(volume)).is(Blocks.OBSERVER),
                OBSERVER_PULSE + " rollback kept the observer");
        record.accept(level.getBlockState(floorLeverPos(volume)).is(Blocks.LEVER)
                        && !level.getBlockState(floorLeverPos(volume)).getValue(LeverBlock.POWERED),
                OBSERVER_PULSE + " rollback left the lever unpowered");
    }

    private static void verifyDispenserTrigger(
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        record.accept(level.getBlockState(dispenserPos(volume)).is(Blocks.DISPENSER),
                DISPENSER_TRIGGER + " rollback kept the dispenser");
        record.accept(!level.getBlockState(dispenserPos(volume)).getValue(DispenserBlock.TRIGGERED),
                DISPENSER_TRIGGER + " rollback left the dispenser idle");
    }

    private static void verifyObserverPiston(
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        BlockPos piston = observerPistonBasePos(volume);
        BlockPos observerHome = observerPistonObserverHomePos(volume);
        BlockPos observerExtended = observerHome.east();

        record.accept(level.getBlockState(piston).is(Blocks.STICKY_PISTON)
                        && !level.getBlockState(piston).getValue(PistonBaseBlock.EXTENDED),
                OBSERVER_PISTON + " rollback left sticky piston retracted");
        record.accept(level.getBlockState(observerHome).is(Blocks.OBSERVER),
                OBSERVER_PISTON + " rollback pulled observer back to the sticky piston");
        record.accept(level.getBlockState(observerExtended).isAir(),
                OBSERVER_PISTON + " rollback cleared the pushed observer cell");
        record.accept(countBlocks(level, volume, Blocks.PISTON_HEAD) == 0,
                OBSERVER_PISTON + " rollback left no stray piston heads");
        record.accept(countBlocks(level, volume, Blocks.MOVING_PISTON) == 0,
                OBSERVER_PISTON + " rollback left no moving piston placeholders");
    }

    private static void verifyClosedObserverPiston(
            ServerLevel level,
            SingleplayerTestVolume volume,
            BiConsumer<Boolean, String> record
    ) {
        BlockPos piston = observerPistonBasePos(volume);
        BlockPos observerHome = closedObserverPistonObserverHomePos(volume);
        BlockPos pairedObserver = closedObserverPistonPairedObserverPos(volume);
        BlockPos observerExtended = observerHome.above();

        record.accept(level.getBlockState(piston).is(Blocks.STICKY_PISTON)
                        && !level.getBlockState(piston).getValue(PistonBaseBlock.EXTENDED),
                CLOSED_OBSERVER_PISTON + " rollback left vertical sticky piston retracted");
        record.accept(level.getBlockState(observerHome).is(Blocks.OBSERVER),
                CLOSED_OBSERVER_PISTON + " rollback pulled the lifted observer home");
        record.accept(level.getBlockState(pairedObserver).is(Blocks.OBSERVER),
                CLOSED_OBSERVER_PISTON + " rollback kept the facing observer in place");
        record.accept(level.getBlockState(observerExtended).isAir(),
                CLOSED_OBSERVER_PISTON + " rollback cleared the lifted observer cell");
        record.accept(countBlocks(level, volume, Blocks.OBSERVER) == 2,
                CLOSED_OBSERVER_PISTON + " rollback left exactly two observers");
        record.accept(countBlocks(level, volume, Blocks.PISTON_HEAD) == 0,
                CLOSED_OBSERVER_PISTON + " rollback left no orphan piston heads");
        record.accept(countBlocks(level, volume, Blocks.MOVING_PISTON) == 0,
                CLOSED_OBSERVER_PISTON + " rollback left no moving piston placeholders");
    }

    private static void placeFloorLever(ServerLevel level, BlockPos lever, Direction facing) {
        placeSupport(level, lever.below(), Blocks.BLUE_CONCRETE);
        level.setBlock(lever, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, facing)
                .setValue(LeverBlock.POWERED, false), 3);
    }

    private static void placeSupport(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), 3);
    }

    private static StructureFixtureControl control(
            SingleplayerTestVolume volume,
            BlockPos pos,
            Direction face,
            String label
    ) {
        return new StructureFixtureControl(pos.subtract(volume.min()), pos, face, label);
    }

    private static int countBlocks(ServerLevel level, SingleplayerTestVolume volume, Block block) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(volume.min(), volume.max())) {
            if (level.getBlockState(pos).is(block)) {
                count += 1;
            }
        }
        return count;
    }

    private static BlockPos pos(SingleplayerTestVolume volume, int x, int y, int z) {
        return volume.min().offset(x, y, z);
    }

    private static BlockPos floorLeverPos(SingleplayerTestVolume volume) {
        return pos(volume, 2, 1, 2);
    }

    private static BlockPos torchInverterBlockPos(SingleplayerTestVolume volume) {
        return pos(volume, 5, 1, 3);
    }

    private static BlockPos torchInverterLeverPos(SingleplayerTestVolume volume) {
        return torchInverterBlockPos(volume).north();
    }

    private static BlockPos repeaterMainPos(SingleplayerTestVolume volume) {
        return pos(volume, 4, 1, 5);
    }

    private static BlockPos repeaterLeverPos(SingleplayerTestVolume volume) {
        return pos(volume, 2, 1, 7);
    }

    private static BlockPos repeaterLockPos(SingleplayerTestVolume volume) {
        return pos(volume, 4, 1, 6);
    }

    private static BlockPos comparatorPos(SingleplayerTestVolume volume) {
        return pos(volume, 4, 1, 7);
    }

    private static BlockPos observerPulseObserverPos(SingleplayerTestVolume volume) {
        return pos(volume, 3, 1, 2);
    }

    private static BlockPos dispenserPos(SingleplayerTestVolume volume) {
        return pos(volume, 4, 1, 10);
    }

    private static BlockPos dispenserLeverPos(SingleplayerTestVolume volume) {
        return dispenserPos(volume).above();
    }

    private static BlockPos observerPistonLeverPos(SingleplayerTestVolume volume) {
        return pos(volume, 2, 1, 2);
    }

    private static BlockPos observerPistonBasePos(SingleplayerTestVolume volume) {
        return pos(volume, 3, 1, 2);
    }

    private static BlockPos observerPistonObserverHomePos(SingleplayerTestVolume volume) {
        return pos(volume, 4, 1, 2);
    }

    private static BlockPos closedObserverPistonObserverHomePos(SingleplayerTestVolume volume) {
        return observerPistonBasePos(volume).above();
    }

    private static BlockPos closedObserverPistonPairedObserverPos(SingleplayerTestVolume volume) {
        return closedObserverPistonObserverHomePos(volume).east();
    }
}
