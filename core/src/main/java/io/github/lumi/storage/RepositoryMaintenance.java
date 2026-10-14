package io.github.lumi.storage;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.Objects;
import java.util.WeakHashMap;

/** Invalidates a GC snapshot when a publication starts, without locking its graph walk. */
public final class RepositoryMaintenance {
    private static final WeakHashMap<Path, WeakReference<RepositoryMaintenance>> REPOSITORIES =
            new WeakHashMap<>();
    // Keep the weak registry key alive while any repository owns this coordinator.
    private final Path root;
    private long revision;
    private int publications;

    private RepositoryMaintenance(Path root) {
        this.root = root;
    }

    public static synchronized RepositoryMaintenance forRepository(Path root) {
        Path key = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        WeakReference<RepositoryMaintenance> reference = REPOSITORIES.get(key);
        RepositoryMaintenance existing = reference == null ? null : reference.get();
        if (existing != null) return existing;
        RepositoryMaintenance created = new RepositoryMaintenance(key);
        REPOSITORIES.put(key, new WeakReference<>(created));
        return created;
    }

    public synchronized Publication beginPublication() {
        revision++;
        publications++;
        return new Publication();
    }

    public synchronized Sweep beginSweep() {
        return new Sweep(revision, publications == 0);
    }

    public final class Publication implements AutoCloseable {
        private boolean closed;

        private Publication() { }

        @Override
        public void close() {
            synchronized (RepositoryMaintenance.this) {
                if (closed) return;
                closed = true;
                publications--;
            }
        }
    }

    public final class Sweep {
        private final long expectedRevision;
        private final boolean idleAtStart;

        private Sweep(long expectedRevision, boolean idleAtStart) {
            this.expectedRevision = expectedRevision;
            this.idleAtStart = idleAtStart;
        }

        public boolean isCurrent() {
            synchronized (RepositoryMaintenance.this) {
                return idleAtStart && publications == 0 && revision == expectedRevision;
            }
        }

        /** The action must delete only one loose file or one pack/index pair. */
        public boolean deleteIfCurrent(Deletion deletion) throws IOException {
            synchronized (RepositoryMaintenance.this) {
                if (!isCurrent()) return false;
                deletion.run();
                return true;
            }
        }
    }

    @FunctionalInterface
    public interface Deletion {
        void run() throws IOException;
    }
}
