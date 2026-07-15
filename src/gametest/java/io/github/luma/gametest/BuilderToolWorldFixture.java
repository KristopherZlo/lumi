package io.github.luma.gametest;

import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Owns deterministic states written through supported builder-tool runtimes. */
final class BuilderToolWorldFixture {

    private static final String AUTHOR = "Lumi builder-tool gate";

    private final String projectName;
    private final String projectId;
    private final List<BlockPos> positions;
    private final WorldEditRuntimeEditDriver worldEdit = new WorldEditRuntimeEditDriver();
    private final AxiomRuntimeEditDriver axiom = new AxiomRuntimeEditDriver();

    private BuilderToolWorldFixture(String projectName, String projectId, BlockPos origin) {
        this.projectName = projectName;
        this.projectId = projectId;
        this.positions = List.of(origin, origin.east(), origin.south());
    }

    static BuilderToolWorldFixture create(ServerLevel level, BlockPos origin) throws IOException {
        var project = new ProjectService().ensureWorldProject(level, AUTHOR);
        return new BuilderToolWorldFixture(project.name(), project.id().toString(), origin);
    }

    void applyWorldEditState(ServerLevel level) {
        this.worldEdit.apply(level, this.state(
                Blocks.STONE.defaultBlockState(),
                Blocks.GOLD_BLOCK.defaultBlockState(),
                Blocks.COPPER_BLOCK.defaultBlockState()
        ));
    }

    void applyAxiomState(ServerLevel level, ServerPlayer player) {
        this.axiom.apply(level, player, this.state(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                Blocks.EMERALD_BLOCK.defaultBlockState(),
                Blocks.REDSTONE_BLOCK.defaultBlockState()
        ));
    }

    void assertWorldEditState(ServerLevel level) {
        this.assertBlocks(level, Blocks.STONE, Blocks.GOLD_BLOCK, Blocks.COPPER_BLOCK);
    }

    void assertAxiomState(ServerLevel level) {
        this.assertBlocks(level, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.REDSTONE_BLOCK);
    }

    void assertDraftSource(ServerLevel level, WorldMutationSource expected) throws IOException {
        RecoveryDraft draft = HistoryCaptureManager.getInstance()
                .snapshotDraft(level.getServer(), this.projectId)
                .orElseThrow(() -> new AssertionError("Missing " + expected + " working draft"));
        if (draft.mutationSource() != expected || draft.changes().size() != this.positions.size()) {
            throw new AssertionError("Unexpected builder-tool draft: source=" + draft.mutationSource()
                    + ", changes=" + draft.changes().size());
        }
    }

    String projectName() {
        return this.projectName;
    }

    String projectId() {
        return this.projectId;
    }

    private Map<BlockPos, BlockState> state(BlockState first, BlockState second, BlockState third) {
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        changes.put(this.positions.get(0), first);
        changes.put(this.positions.get(1), second);
        changes.put(this.positions.get(2), third);
        return changes;
    }

    private void assertBlocks(ServerLevel level, Block first, Block second, Block third) {
        List<Block> expected = List.of(first, second, third);
        for (int index = 0; index < this.positions.size(); index++) {
            BlockPos pos = this.positions.get(index);
            if (!level.getBlockState(pos).is(expected.get(index))) {
                throw new AssertionError("Unexpected block at " + pos + ": " + level.getBlockState(pos));
            }
        }
    }
}
