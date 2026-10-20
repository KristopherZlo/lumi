package io.github.lumi.client.integration;

import com.moulberry.axiom.world_modification.BlockOrBiomeBuffer;
import com.moulberry.axiom.world_modification.HistoryBuffer;
import com.moulberry.axiom.world_modification.HistoryEntry;
import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.storage.repository.AxiomHistoryLinkRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;

/** Hashes the bytes Axiom already serialized; no second native payload or re-encoding. */
public final class AxiomNativeHistoryFiles {
    public record Binding(UUID epoch, UUID entry, int position) { }
    private static final Map<HistoryEntry<?>, Binding> BINDINGS = new IdentityHashMap<>();
    private static final ThreadLocal<Save> SAVING = new ThreadLocal<>();
    private static final ThreadLocal<Load> LOADING = new ThreadLocal<>();
    private static final Map<HistoryBuffer<?>, Map<HistoryEntry<?>, Binding>> LOADED = new ConcurrentHashMap<>();
    private AxiomNativeHistoryFiles() { }

    public static Binding binding(HistoryEntry<?> entry) { return BINDINGS.get(entry); }
    static void register(HistoryEntry<?> entry, Binding binding) { BINDINGS.put(entry, binding); }
    static void forget(HistoryEntry<?> entry) { BINDINGS.remove(entry); }

    public static void push(Path path, HistoryEntry<?> entry, int position, Binding binding, Runnable original) {
        if (binding == null) { original.run(); return; }
        Save previous = SAVING.get();
        var save = new Save(entry);
        SAVING.set(save);
        try {
            original.run();
            if (save.fingerprint != null) new AxiomHistoryLinkRepository(path).put(position,
                    new AxiomHistoryLinkRepository.Link(binding.epoch(), binding.entry(), save.fingerprint));
        } catch (IOException failed) {
            LumiMod.LOGGER.warn("Cannot retain native Axiom entry identity; portable Lumi history remains available", failed);
        } finally { if (previous == null) SAVING.remove(); else SAVING.set(previous); }
    }

    public static void serialized(HistoryEntry<?> entry, FriendlyByteBuf bytes, int start) {
        Save save = SAVING.get();
        if (save != null && save.entry == entry) save.fingerprint = fingerprint(bytes, start, bytes.writerIndex());
    }

    public static HistoryEntry<BlockOrBiomeBuffer> decoded(FriendlyByteBuf bytes,
            Supplier<HistoryEntry<BlockOrBiomeBuffer>> original) {
        int start = bytes.readerIndex();
        var entry = original.get();
        var loading = LOADING.get();
        if (loading != null) {
            loading.entry = entry; loading.bytes = bytes;
            loading.start = start; loading.end = bytes.readerIndex();
        }
        return entry;
    }

    public static void positioned(int position, HistoryEntry<?> entry) {
        Load loading = LOADING.get();
        if (loading == null) return;
        var link = loading.links.get(position);
        if (link != null && loading.entry == entry
                && link.fingerprint().equals(fingerprint(loading.bytes, loading.start, loading.end)))
            loading.bindings.put(entry, new Binding(link.epoch(), link.entry(), position));
        loading.entry = null; loading.bytes = null;
    }

    public static HistoryBuffer<BlockOrBiomeBuffer> load(Path path, Supplier<HistoryBuffer<BlockOrBiomeBuffer>> original) {
        Map<Integer, AxiomHistoryLinkRepository.Link> links = Map.of();
        try { links = new AxiomHistoryLinkRepository(path).read(); }
        catch (IOException failed) { LumiMod.LOGGER.warn("Cannot verify native Axiom history links", failed); }
        var loading = new Load(links);
        LOADING.set(loading);
        try {
            var buffer = original.get();
            // Pending native loads can finish after leaving a world; keep that handoff bounded.
            if (LOADED.size() >= 4) LOADED.clear();
            LOADED.put(buffer, loading.bindings);
            return buffer;
        } finally { LOADING.remove(); }
    }

    static Map<HistoryEntry<?>, Binding> take(HistoryBuffer<?> buffer) {
        var result = LOADED.remove(buffer);
        return result == null ? Map.of() : result;
    }

    static void disconnect() { BINDINGS.clear(); LOADED.clear(); }

    private static ObjectId fingerprint(FriendlyByteBuf bytes, int start, int end) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes.nioBuffer(start, end - start));
            return new ObjectId(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class Save {
        private final HistoryEntry<?> entry;
        private ObjectId fingerprint;
        private Save(HistoryEntry<?> entry) { this.entry = entry; }
    }

    private static final class Load {
        private final Map<Integer, AxiomHistoryLinkRepository.Link> links;
        private final Map<HistoryEntry<?>, Binding> bindings = new IdentityHashMap<>();
        private HistoryEntry<?> entry;
        private FriendlyByteBuf bytes;
        private int start, end;
        private Load(Map<Integer, AxiomHistoryLinkRepository.Link> links) { this.links = links; }
    }
}
