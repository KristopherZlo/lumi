package io.github.lumi.minecraft.world;

import com.moulberry.axiom.packets.AxiomServerboundSetBuffer;
import com.moulberry.axiom.world_modification.BlockBuffer;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.SectionKey;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Uses the same server buffer writer as native Axiom Undo, under Lumi's operation ownership. */
public final class AxiomSectionWriter implements BiPredicate<SectionKey, DecodedSection> {
    private final ServerLevel level;
    private final ServerPlayer actor;
    private boolean reported;

    public AxiomSectionWriter(ServerLevel level, ServerPlayer actor) {
        this.level = level;
        this.actor = actor;
    }

    @Override public boolean test(SectionKey key, DecodedSection section) {
        if (section.blockStates().contains(BlockBuffer.EMPTY_STATE)) return false;
        if (!reported) {
            LumiMod.LOGGER.info("Lumi shared replay uses the native Axiom buffer executor");
            reported = true;
        }
        var buffer = new BlockBuffer();
        // Axiom stores section coordinates with BlockPos's wire layout.
        buffer.putSection(BlockPos.asLong(key.chunkX(), key.sectionY(), key.chunkZ()), section.copyBlockStates());
        AxiomServerboundSetBuffer.applyBlockBufferServer(buffer, level, null, actor);
        return true;
    }
}
