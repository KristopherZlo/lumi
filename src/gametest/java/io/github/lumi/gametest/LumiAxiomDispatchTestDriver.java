package io.github.lumi.gametest;

import com.moulberry.axiom.world_modification.BlockBuffer;
import com.moulberry.axiom.world_modification.DispatchTask;
import io.github.lumi.client.integration.AxiomClientWorkBarrier;
import io.github.lumi.network.AxiomDispatchPayload;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Retains one native task so the two TCP clients can interleave its section packets. */
final class LumiAxiomDispatchTestDriver {
    private final ClientGameTestContext context;
    private DispatchTask task;

    LumiAxiomDispatchTestDriver(ClientGameTestContext context) { this.context = context; }

    static int changedCells(io.github.lumi.domain.service.LiveActionJournal.Plan plan) {
        int cells = plan.expected().size();
        for (var section : plan.sections().values())
            for (int cell = 0; cell < io.github.lumi.domain.model.SectionBlob.BLOCK_COUNT; cell++)
                if (section.changed(cell)) cells++;
        return cells;
    }

    void begin(BlockPos origin, BlockState state) {
        ready("");
        context.runOnClient(client -> {
            var buffer = new BlockBuffer();
            for (int x = 0; x <= 32; x += 16) {
                BlockPos position = origin.offset(x, 0, 0);
                buffer.set(position.getX(), position.getY(), position.getZ(), state);
            }
            if (buffer.getSectionCount() != 3) throw new AssertionError("TCP fixture needs three sections");
            task = DispatchTask.blockOrBiome(client.level.dimension(), buffer);
        });
    }

    void tick() {
        context.runOnClient(client -> {
            if (task == null || task.isDone()) throw new AssertionError("No unfinished native dispatch");
            task.tick(1);
        });
    }

    void ready(String dimension) {
        context.waitFor(client -> client.level != null && client.player != null
                && (dimension.isEmpty() || client.level.dimension().identifier().toString().equals(dimension))
                && io.github.lumi.client.LumiClient.history().state().snapshot().filter(snapshot ->
                        snapshot.dimensionId().equals(client.level.dimension().identifier().toString())).isPresent()
                && ClientPlayNetworking.canSend(AxiomDispatchPayload.TYPE)
                && !AxiomClientWorkBarrier.isPaused(), 2400);
    }
}
