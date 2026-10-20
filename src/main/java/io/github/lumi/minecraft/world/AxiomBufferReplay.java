package io.github.lumi.minecraft.world;

import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.render.regions.ChunkedBooleanRegion;
import com.moulberry.axiom.world_modification.BlockBuffer;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.LiveActionJournal;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/** Uses Axiom's Undo packet handler, with Lumi's existing exact readback and persistence. */
public final class AxiomBufferReplay implements WorldStateApply.ApplySession {
    public static final int EAGER_SECTION_LIMIT = 64;
    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final PreparedWorldAccess world;
    private final PreparedMinecraftState target;
    private final Map<SectionKey, BlockBuffer> buffers;
    private final Iterator<SectionKey> remaining;
    private final WorldStateApply.ApplySession verification;

    public static boolean supports(LiveActionJournal.Plan plan, ServerLevel level) {
        // Section endpoints are produced by AxiomSetBufferMixin; scalar edits use Lumi's executor.
        // ponytail: cap eager native preparation; cold or larger actions retain streaming replay.
        if (plan.checkpoint().isPresent() || plan.sections().isEmpty() || plan.sections().size() > EAGER_SECTION_LIMIT
                || !plan.expected().isEmpty() || !plan.expectedEntities().isEmpty() || !plan.biomes().isEmpty()) return false;
        long nbtBytes = 0;
        for (var entry : plan.sections().entrySet()) {
            var key = entry.getKey();
            if (level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ()) == null) return false;
            for (var section : List.of(entry.getValue().before(), entry.getValue().after()))
                for (var nbt : section.blockEntities().values()) nbtBytes += nbt.byteSize();
        }
        return nbtBytes <= 16L << 20;
    }

    /** Called only on the preparation worker; no string/NBT decoding is deferred to apply. */
    AxiomBufferReplay(LiveActionJournal.Plan plan, PreparedMinecraftState target,
            PreparedWorldAccess world, ServerLevel level, DimensionFreezeState freeze) {
        this.target = target;
        this.world = world;
        this.level = level;
        this.freeze = freeze;
        buffers = new HashMap<>();
        plan.sections().forEach((key, delta) -> {
            var buffer = new BlockBuffer();
            var decoded = target.sections().get(key);
            for (int cell = 0; cell < SectionBlob.BLOCK_COUNT; cell++) {
                if (!delta.changed(cell)) continue;
                var position = MinecraftPreparedWorldAccess.position(key, cell);
                buffer.set(position.getX(), position.getY(), position.getZ(), decoded.blockStates().get(cell));
            }
            buffers.put(key, buffer);
        });
        remaining = target.sectionKeys().iterator();
        verification = new PreparedWorldMutationSession(target, world, System::nanoTime);
    }

    @Override public boolean applyUntil(long deadline) throws IOException {
        while (remaining.hasNext() && System.nanoTime() < deadline) {
            var key = remaining.next();
            if (level.getChunkSource().getChunkNow(key.chunkX(), key.chunkZ()) == null)
                throw new IOException("Axiom replay chunk unloaded: " + key);
            var decoded = target.sections().get(key);
            var delta = decoded.preparedDelta();
            var existing = world.blockEntityIndexes(key);
            freeze.runAuthorized(() -> AxiomServerboundSetBuffer.applyBlockBufferServer(
                    buffers.get(key), level, new ChunkedBooleanRegion(), null));
            // Pass already-decoded NBT through the common adapter instead of decompressing on the tick.
            for (int cell : existing) if (!decoded.blockEntities().containsKey(cell)) world.removeBlockEntity(key, cell);
            for (var entry : decoded.blockEntities().entrySet()) world.loadBlockEntity(key, entry.getKey(), entry.getValue());
            boolean nbtChanged = delta.blockEntitiesChanged();
            world.finishChunk(ChunkCoordinate.from(key), List.of(new SectionApplyResult(key,
                    delta.changedCells(), delta.changedCount(), nbtChanged, delta.lightColumns())), nbtChanged);
        }
        return !remaining.hasNext();
    }
    @Override public WorldStateApply.Verification verifyUntil(long deadline) throws IOException { return verification.verifyUntil(deadline); }
    @Override public boolean persistUntil(long deadline) throws IOException { return verification.persistUntil(deadline); }
    @Override public boolean repairUntil(long deadline) throws IOException { throw new IOException("Native replay repair requires its recovery journal"); }
    @Override public void restartVerification() { verification.restartVerification(); }
    @Override public void close() { verification.close(); buffers.clear(); }
}
