package io.github.luma.minecraft.debug;

import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.minecraft.world.BlockStateNbtCodec;
import io.github.luma.minecraft.world.MechanismStatePolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * File-backed diagnostics for selected-area partial restore investigations.
 */
public final class PartialRestoreDiagnosticsLog {

    private static final int DEFAULT_SELECTED_SCAN_LIMIT = 4_096;
    private static final int DEFAULT_POST_APPLY_CHECK_LIMIT = 32_768;
    private static final int SAMPLE_LIMIT = 64;
    private static final String[] TRACE_ID_FRAGMENTS = {
            "redstone", "repeater", "comparator", "piston", "observer", "lever", "button",
            "pressure_plate", "tripwire", "target", "rail", "detector", "daylight", "sculk", "crafter"
    };

    private final MechanismStatePolicy mechanismStatePolicy = new MechanismStatePolicy();

    public boolean enabled(PartialRestoreRequest request) {
        return LumaDiagnosticsLog.partialRestoreEnabled()
                && request != null
                && request.restoreMode() == PartialRestoreMode.SELECTED_AREA;
    }

    public void logSelectedArea(ServerLevel level, BuildProject project, PartialRestoreRequest request) {
        if (!this.enabled(request) || level == null) {
            return;
        }
        SelectedAreaReport report = this.selectedAreaReport(
                request.bounds(),
                point -> level.getBlockState(point.toBlockPos()),
                scanLimit()
        );
        LumaDiagnosticsLog.partialRestoreEvent("selected-current", this.common(project, request)
                + ", volume=" + volume(request.bounds())
                + ", scannedCells=" + report.scannedCells()
                + ", truncated=" + report.truncated()
                + ", nonAirBlocks=" + report.nonAirBlocks()
                + ", mechanismBlocks=" + report.mechanismBlocks()
                + ", readErrors=" + report.readErrors()
                + ", samples=[" + String.join("; ", report.samples()) + "]");
    }

    public void logPlannedDraft(
            BuildProject project,
            ProjectVariant activeVariant,
            ProjectVersion targetVersion,
            PartialRestoreRequest request,
            RestorePlanMode mode,
            RecoveryDraft draft
    ) {
        if (!this.enabled(request)) {
            return;
        }
        PlanReport report = this.planReport(draft == null ? List.of() : draft.changes(), request.bounds());
        LumaDiagnosticsLog.partialRestoreEvent("planned-target", this.common(project, request)
                + ", activeVariant=" + value(activeVariant == null ? "" : activeVariant.id())
                + ", activeHead=" + value(activeVariant == null ? "" : activeVariant.headVersionId())
                + ", resolvedTarget=" + value(targetVersion == null ? "" : targetVersion.id())
                + ", planMode=" + mode
                + ", blockChanges=" + report.blockChanges()
                + ", entityChanges=" + (draft == null ? 0 : draft.entityChanges().size())
                + ", deleteTargets=" + report.deleteTargets()
                + ", setTargets=" + report.setTargets()
                + ", mechanismTargets=" + report.mechanismTargets()
                + ", outOfBoundsChanges=" + report.outOfBoundsChanges()
                + ", samples=[" + String.join("; ", report.samples()) + "]");
    }

    public void logPostApplyRemaining(
            ServerLevel level,
            BuildProject project,
            PartialRestoreRequest request,
            RestorePlanMode mode,
            RecoveryDraft draft
    ) {
        if (!this.enabled(request) || level == null) {
            return;
        }
        PostApplyReport report = this.postApplyReport(
                draft == null ? List.of() : draft.changes(),
                point -> level.getBlockState(point.toBlockPos()),
                level,
                request.bounds(),
                postApplyLimit()
        );
        LumaDiagnosticsLog.partialRestoreEvent("post-apply-remaining", this.common(project, request)
                + ", planMode=" + mode
                + ", checkedTargets=" + report.checkedTargets()
                + ", truncated=" + report.truncated()
                + ", mismatchedTargets=" + report.mismatchedTargets()
                + ", expectedAirButLiveNonAir=" + report.expectedAirButLiveNonAir()
                + ", liveMechanismMismatches=" + report.liveMechanismMismatches()
                + ", outOfBoundsTargets=" + report.outOfBoundsTargets()
                + ", decodeErrors=" + report.decodeErrors()
                + ", readErrors=" + report.readErrors()
                + ", samples=[" + String.join("; ", report.samples()) + "]");
    }

    SelectedAreaReport selectedAreaReport(Bounds3i bounds, BlockStateReader reader, int maxCells) {
        if (bounds == null || reader == null || maxCells <= 0) {
            return new SelectedAreaReport(0, 0, 0, 0, bounds != null && volume(bounds) > 0, 0, List.of());
        }
        int scanned = 0;
        int nonAir = 0;
        int mechanism = 0;
        int readErrors = 0;
        List<String> priority = new ArrayList<>();
        List<String> other = new ArrayList<>();

        outer:
        for (int y = bounds.min().y(); y <= bounds.max().y(); y += 1) {
            for (int z = bounds.min().z(); z <= bounds.max().z(); z += 1) {
                for (int x = bounds.min().x(); x <= bounds.max().x(); x += 1) {
                    if (scanned >= maxCells) {
                        break outer;
                    }
                    scanned += 1;
                    BlockPoint point = new BlockPoint(x, y, z);
                    BlockState state;
                    try {
                        state = reader.stateAt(point);
                    } catch (RuntimeException exception) {
                        readErrors += 1;
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    boolean mechanismState = this.isMechanism(state);
                    nonAir += 1;
                    mechanism += mechanismState ? 1 : 0;
                    sample(priority, other, this.format(point) + "=" + this.describe(state), mechanismState);
                }
            }
        }
        return new SelectedAreaReport(scanned, nonAir, mechanism, readErrors, scanned < volume(bounds), volume(bounds),
                samples(priority, other));
    }

    PlanReport planReport(List<StoredBlockChange> changes, Bounds3i bounds) {
        int total = 0;
        int outOfBounds = 0;
        int deletes = 0;
        int sets = 0;
        int mechanism = 0;
        List<String> priority = new ArrayList<>();
        List<String> other = new ArrayList<>();

        for (StoredBlockChange change : changes == null ? List.<StoredBlockChange>of() : changes) {
            if (change == null || change.pos() == null) {
                continue;
            }
            boolean inside = bounds == null || bounds.contains(change.pos());
            boolean mechanismChange = this.isMechanism(change.oldValue()) || this.isMechanism(change.newValue());
            total += 1;
            outOfBounds += inside ? 0 : 1;
            deletes += isAir(change.newValue()) ? 1 : 0;
            sets += isAir(change.newValue()) ? 0 : 1;
            mechanism += mechanismChange ? 1 : 0;
            sample(priority, other, this.describe(change, inside), !inside || mechanismChange);
        }
        return new PlanReport(total, outOfBounds, deletes, sets, mechanism, samples(priority, other));
    }

    PostApplyReport postApplyReport(
            List<StoredBlockChange> changes,
            BlockStateReader reader,
            ServerLevel level,
            Bounds3i bounds,
            int maxChecks
    ) {
        if (reader == null || maxChecks <= 0) {
            return new PostApplyReport(0, 0, 0, 0, 0, 0, 0, changes != null && !changes.isEmpty(), List.of());
        }
        int checked = 0;
        int mismatched = 0;
        int expectedAirLiveNonAir = 0;
        int liveMechanism = 0;
        int outOfBounds = 0;
        int decodeErrors = 0;
        int readErrors = 0;
        List<String> priority = new ArrayList<>();
        List<String> other = new ArrayList<>();
        List<StoredBlockChange> source = changes == null ? List.of() : changes;

        for (StoredBlockChange change : source) {
            if (change == null || change.pos() == null) {
                continue;
            }
            if (checked >= maxChecks) {
                break;
            }
            checked += 1;
            boolean inside = bounds == null || bounds.contains(change.pos());
            outOfBounds += inside ? 0 : 1;
            BlockState expected;
            try {
                StatePayload target = change.newValue() == null ? StatePayload.air() : change.newValue();
                expected = BlockStateNbtCodec.deserializeBlockState(level, target.stateTag());
            } catch (IOException | RuntimeException exception) {
                decodeErrors += 1;
                continue;
            }
            BlockState live;
            try {
                live = reader.stateAt(change.pos());
            } catch (RuntimeException exception) {
                readErrors += 1;
                continue;
            }
            if (Objects.equals(live, expected)) {
                continue;
            }
            boolean expectedAir = expected == null || expected.isAir();
            boolean liveNonAir = live != null && !live.isAir();
            boolean liveMechanismState = this.isMechanism(live);
            mismatched += 1;
            expectedAirLiveNonAir += expectedAir && liveNonAir ? 1 : 0;
            liveMechanism += liveMechanismState ? 1 : 0;
            sample(priority, other, this.format(change.pos())
                    + " inSelection=" + inside
                    + " expected=" + this.describe(expected)
                    + " live=" + this.describe(live)
                    + " plannedOld=" + this.describe(change.oldValue()),
                    !inside || expectedAir && liveNonAir || liveMechanismState);
        }
        return new PostApplyReport(checked, mismatched, expectedAirLiveNonAir, liveMechanism, outOfBounds,
                decodeErrors, readErrors, checked < source.size(), samples(priority, other));
    }

    private String common(BuildProject project, PartialRestoreRequest request) {
        return "project=" + value(project == null ? request.projectName() : project.name())
                + ", projectId=" + value(project == null ? "" : project.id().toString())
                + ", target=" + value(request.targetVersionId())
                + ", mode=" + request.restoreMode()
                + ", regionSource=" + request.regionSource()
                + ", bounds=" + this.describe(request.bounds());
    }

    private String describe(StoredBlockChange change, boolean inside) {
        return this.format(change.pos()) + " inSelection=" + inside + " "
                + this.describe(change.oldValue()) + " -> " + this.describe(change.newValue());
    }

    private String describe(StatePayload payload) {
        if (payload == null) {
            return "minecraft:air";
        }
        return payload.toStateSnbt() + (payload.blockEntityTag() == null ? "" : "+be");
    }

    private String describe(BlockState state) {
        if (state == null) {
            return "minecraft:air";
        }
        String blockId = this.blockId(state);
        if (state.getProperties().isEmpty()) {
            return blockId;
        }
        List<String> properties = new ArrayList<>();
        for (Property<?> property : state.getProperties()) {
            properties.add(property.getName() + "=" + this.propertyValue(state, property));
        }
        properties.sort(Comparator.naturalOrder());
        return blockId + "[" + String.join(",", properties) + "]";
    }

    private String describe(Bounds3i bounds) {
        return bounds == null ? "<none>" : this.format(bounds.min()) + ".." + this.format(bounds.max());
    }

    private boolean isMechanism(BlockState state) {
        return state != null && (this.mechanismStatePolicy.isMechanismRelevant(state)
                || this.shouldTrace(this.blockId(state)));
    }

    private boolean isMechanism(StatePayload payload) {
        return payload != null && this.shouldTrace(payload.blockId());
    }

    private boolean shouldTrace(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        String normalized = blockId.toLowerCase(Locale.ROOT);
        for (String fragment : TRACE_ID_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return normalized.equals("minecraft:dispenser")
                || normalized.equals("minecraft:dropper")
                || normalized.equals("minecraft:slime_block")
                || normalized.equals("minecraft:honey_block");
    }

    private String blockId(BlockState state) {
        return state == null || state.is(Blocks.AIR)
                ? "minecraft:air"
                : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private String propertyValue(BlockState state, Property<?> property) {
        return this.propertyValueUnchecked(state, property);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String propertyValueUnchecked(BlockState state, Property property) {
        return property.getName((Comparable) state.getValue(property)).toLowerCase(Locale.ROOT);
    }

    private static void sample(List<String> priority, List<String> other, String sample, boolean prioritized) {
        List<String> target = prioritized ? priority : other;
        if (target.size() < SAMPLE_LIMIT && sample != null && !sample.isBlank()) {
            target.add(sample);
        }
    }

    private static List<String> samples(List<String> priority, List<String> other) {
        List<String> merged = new ArrayList<>(priority);
        for (String sample : other) {
            if (merged.size() >= SAMPLE_LIMIT) {
                break;
            }
            merged.add(sample);
        }
        return List.copyOf(merged);
    }

    private static boolean isAir(StatePayload payload) {
        return payload == null || "minecraft:air".equals(payload.blockId());
    }

    private static long volume(Bounds3i bounds) {
        return bounds == null ? 0L : bounds.volume();
    }

    private static int scanLimit() {
        return Math.max(1, Integer.getInteger("lumi.partialRestoreLog.maxSelectedCells", DEFAULT_SELECTED_SCAN_LIMIT));
    }

    private static int postApplyLimit() {
        return Math.max(1, Integer.getInteger("lumi.partialRestoreLog.maxPostApplyChecks", DEFAULT_POST_APPLY_CHECK_LIMIT));
    }

    private String format(BlockPoint point) {
        return point == null ? "unknown" : point.x() + "," + point.y() + "," + point.z();
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    @FunctionalInterface
    interface BlockStateReader {
        BlockState stateAt(BlockPoint point);
    }

    record SelectedAreaReport(int scannedCells, int nonAirBlocks, int mechanismBlocks, int readErrors,
                              boolean truncated, long volume, List<String> samples) {
        SelectedAreaReport { samples = samples == null ? List.of() : List.copyOf(samples); }
    }

    record PlanReport(int blockChanges, int outOfBoundsChanges, int deleteTargets, int setTargets,
                      int mechanismTargets, List<String> samples) {
        PlanReport { samples = samples == null ? List.of() : List.copyOf(samples); }
    }

    record PostApplyReport(int checkedTargets, int mismatchedTargets, int expectedAirButLiveNonAir,
                           int liveMechanismMismatches, int outOfBoundsTargets, int decodeErrors,
                           int readErrors, boolean truncated, List<String> samples) {
        PostApplyReport { samples = samples == null ? List.of() : List.copyOf(samples); }
    }
}
