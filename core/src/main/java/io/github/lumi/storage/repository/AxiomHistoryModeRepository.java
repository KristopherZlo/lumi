package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.AxiomHistoryMode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Atomic server-wide selection. An open server keeps its startup mode. */
public final class AxiomHistoryModeRepository {
    private static final int MAGIC = 0x4c41484d;
    private static final int BYTES = Integer.BYTES + Long.BYTES + 1;
    private final Path file;

    public AxiomHistoryModeRepository(Path worldRoot) {
        file = worldRoot.toAbsolutePath().normalize().resolve("lumi/history/server/axiom-history.bin");
    }

    public record State(AxiomHistoryMode mode, long revision) {
        public State {
            Objects.requireNonNull(mode);
            if (revision < 0) throw new IllegalArgumentException("Invalid Axiom settings revision");
        }
    }

    public synchronized State read() throws IOException {
        if (!Files.exists(file)) return new State(AxiomHistoryMode.NATIVE_AXIOM, 0);
        byte[] bytes = RepositoryFileReader.read(file, BYTES);
        if (bytes.length != BYTES) throw new IOException("Invalid Axiom history settings size");
        var input = ByteBuffer.wrap(bytes);
        int magic = input.getInt();
        long revision = input.getLong();
        int mode = Byte.toUnsignedInt(input.get());
        if (magic != MAGIC || revision < 0 || mode >= AxiomHistoryMode.values().length)
            throw new IOException("Invalid Axiom history settings");
        return new State(AxiomHistoryMode.values()[mode], revision);
    }

    public synchronized State write(AxiomHistoryMode mode, long expectedRevision) throws IOException {
        Objects.requireNonNull(mode);
        State previous = read();
        if (previous.revision() != expectedRevision) throw new IOException("lumi.axiom.settings_changed");
        if (previous.mode() == mode) return previous;
        State next = new State(mode, Math.addExact(previous.revision(), 1));
        AtomicFileWriter.replace(file, ByteBuffer.allocate(BYTES).putInt(MAGIC)
                .putLong(next.revision()).put((byte) mode.ordinal()).array());
        return next;
    }
}
