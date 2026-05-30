package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.service.ProjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

record HistoryJourneyCheckpoint(
        String label,
        StructureFixtureSnapshot worldSnapshot,
        String activeVariantId,
        Map<String, String> variantHeads,
        int versionCount
) {

    HistoryJourneyCheckpoint {
        label = label == null ? "" : label;
        activeVariantId = activeVariantId == null ? "" : activeVariantId;
        variantHeads = variantHeads == null ? Map.of() : Map.copyOf(variantHeads);
    }

    static HistoryJourneyCheckpoint capture(
            String label,
            ServerLevel level,
            SingleplayerTestVolume volume,
            ProjectService projectService,
            MinecraftServer server,
            String projectName
    ) throws Exception {
        var project = projectService.loadProject(server, projectName);
        LinkedHashMap<String, String> heads = new LinkedHashMap<>();
        for (ProjectVariant variant : projectService.loadVariants(server, projectName)) {
            heads.put(variant.id(), variant.headVersionId());
        }
        return new HistoryJourneyCheckpoint(
                label,
                StructureFixtureSnapshot.capture(level, volume),
                project.activeVariantId(),
                heads,
                projectService.loadVersions(server, projectName).size()
        );
    }

    static HistoryJourneyCheckpoint composeSelectedRegion(
            String label,
            HistoryJourneyCheckpoint current,
            HistoryJourneyCheckpoint selectedSource,
            Bounds3i selection,
            String activeVariantId,
            Map<String, String> variantHeads,
            int versionCount
    ) {
        return new HistoryJourneyCheckpoint(
                label,
                composeSelectedSnapshot(current.worldSnapshot(), selectedSource.worldSnapshot(), selection),
                activeVariantId,
                variantHeads,
                versionCount
        );
    }

    HistoryJourneyCheckpoint withLabel(String label) {
        return new HistoryJourneyCheckpoint(
                label,
                this.worldSnapshot,
                this.activeVariantId,
                this.variantHeads,
                this.versionCount
        );
    }

    HistoryJourneyCheckpoint withProjectState(
            String activeVariantId,
            Map<String, String> variantHeads,
            int versionCount
    ) {
        return new HistoryJourneyCheckpoint(
                this.label,
                this.worldSnapshot,
                activeVariantId,
                variantHeads,
                versionCount
        );
    }

    boolean worldMatches(HistoryJourneyCheckpoint actual) {
        return actual != null && this.worldSnapshot.matches(actual.worldSnapshot);
    }

    void assertMatches(HistoryJourneyCheckpoint actual) {
        if (actual == null) {
            throw new AssertionError("Checkpoint " + this.label + " produced no actual snapshot");
        }
        if (!this.worldSnapshot.matches(actual.worldSnapshot)) {
            throw new AssertionError("Checkpoint " + this.label + " world mismatch: "
                    + this.worldSnapshot.diff(actual.worldSnapshot));
        }
        if (!Objects.equals(this.activeVariantId, actual.activeVariantId)) {
            throw new AssertionError("Checkpoint " + this.label + " active variant mismatch: expected="
                    + this.activeVariantId + " actual=" + actual.activeVariantId);
        }
        if (this.versionCount != actual.versionCount) {
            throw new AssertionError("Checkpoint " + this.label + " version count mismatch: expected="
                    + this.versionCount + " actual=" + actual.versionCount);
        }
        if (!this.variantHeads.equals(actual.variantHeads)) {
            throw new AssertionError("Checkpoint " + this.label + " variant heads mismatch: expected="
                    + this.variantHeads + " actual=" + actual.variantHeads);
        }
    }

    void assertWorldMatches(HistoryJourneyCheckpoint actual) {
        if (actual == null || !this.worldSnapshot.matches(actual.worldSnapshot)) {
            throw new AssertionError("Checkpoint " + this.label + " world mismatch: "
                    + this.worldSnapshot.diff(actual == null ? null : actual.worldSnapshot));
        }
    }

    private static StructureFixtureSnapshot composeSelectedSnapshot(
            StructureFixtureSnapshot current,
            StructureFixtureSnapshot selectedSource,
            Bounds3i selection
    ) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<BlockPos, ?> blocks = new LinkedHashMap(current.blocks());
        for (BlockPos pos : current.blocks().keySet()) {
            if (contains(selection, pos)) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Map writableBlocks = blocks;
                writableBlocks.put(pos, selectedSource.blocks().get(pos));
            }
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map typedBlocks = blocks;
        return new StructureFixtureSnapshot(typedBlocks, current.entities());
    }

    private static boolean contains(Bounds3i selection, BlockPos pos) {
        return selection != null && selection.contains(new BlockPoint(pos.getX(), pos.getY(), pos.getZ()));
    }
}
