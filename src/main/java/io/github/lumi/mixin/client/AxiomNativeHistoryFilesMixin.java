package io.github.lumi.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import com.moulberry.axiom.world_modification.HistoryEntry;
import com.moulberry.axiom.world_modification.HistoryIO;
import io.github.lumi.client.integration.AxiomNativeHistoryFiles;
import java.nio.file.Path;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native files retain their original format; the small identity sidecar is independently verified. */
@Mixin(value = HistoryIO.class, remap = false)
abstract class AxiomNativeHistoryFilesMixin {
    @WrapMethod(method = "pushEntry")
    private static void lumi$saveLink(Path path, HistoryEntry<?> entry, int position, int size, Operation<Void> original) {
        AxiomNativeHistoryFiles.push(path, entry, position, AxiomNativeHistoryFiles.binding(entry),
                () -> original.call(path, entry, position, size));
    }

    @WrapOperation(method = "pushEntry", at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/world_modification/HistoryEntry;save"))
    private static void lumi$fingerprint(HistoryEntry<?> entry, FriendlyByteBuf bytes, Operation<Void> original) {
        int start = bytes.writerIndex();
        original.call(entry, bytes);
        AxiomNativeHistoryFiles.serialized(entry, bytes, start);
    }

    @WrapMethod(method = "loadHistory")
    private static HistoryBuffer<BlockOrBiomeBuffer> lumi$loadLinks(Path path, Operation<HistoryBuffer<BlockOrBiomeBuffer>> original) {
        return AxiomNativeHistoryFiles.load(path, () -> original.call(path));
    }

    @WrapOperation(method = "loadHistory", at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/world_modification/HistoryEntry;load"))
    private static HistoryEntry<BlockOrBiomeBuffer> lumi$verifyBytes(FriendlyByteBuf bytes, Operation<HistoryEntry<BlockOrBiomeBuffer>> original) {
        return AxiomNativeHistoryFiles.decoded(bytes, () -> original.call(bytes));
    }

    @WrapOperation(method = "loadHistory", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;"))
    private static Object lumi$verifyPosition(Int2ObjectMap<Object> entries, int position, Object entry, Operation<Object> original) {
        AxiomNativeHistoryFiles.positioned(position, (HistoryEntry<?>) entry);
        return original.call(entries, position, entry);
    }
}
