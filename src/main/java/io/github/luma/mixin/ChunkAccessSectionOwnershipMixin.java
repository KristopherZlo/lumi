package io.github.luma.mixin;

import io.github.luma.minecraft.capture.ChunkSectionOwnershipRegistry;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkAccess.class)
abstract class ChunkAccessSectionOwnershipMixin {

    @Unique
    private static final ChunkSectionOwnershipRegistry LUMA_SECTION_OWNERSHIP =
            ChunkSectionOwnershipRegistry.getInstance();

    @Inject(method = "getSections", at = @At("RETURN"))
    private void luma$registerReturnedSections(CallbackInfoReturnable<LevelChunkSection[]> cir) {
        LUMA_SECTION_OWNERSHIP.register((ChunkAccess) (Object) this, cir.getReturnValue());
    }

    @Inject(method = "getSection", at = @At("RETURN"))
    private void luma$registerReturnedSection(int sectionIndex, CallbackInfoReturnable<LevelChunkSection> cir) {
        LUMA_SECTION_OWNERSHIP.register((ChunkAccess) (Object) this, sectionIndex, cir.getReturnValue());
    }
}
