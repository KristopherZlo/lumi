package io.github.lumi.storage.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies only representation-neutral metadata into an unpublished generation. */
public final class HistoryMetadataTransfer {
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    public void copy(Path source, Path target) throws IOException {
        // Decode first; invalid metadata must never gain a verification marker.
        new ActiveBranchRepository(source).read();
        new ActiveWorkspaceRepository(source).read();
        new WorkingIndexRepository(source).readState();
        var workspaces = new WorkspaceRepository(source).list();
        copyFile(source.resolve("refs/active.bin"), target.resolve("refs/active.bin"));
        copyFile(source.resolve("working/index.bin"), target.resolve("working/index.bin"));
        copyFile(source.resolve("workspaces/active.bin"), target.resolve("workspaces/active.bin"));
        for (var workspace : workspaces) {
            if (!new WorkspaceRepository(source).read(workspace.id()).filter(workspace::equals).isPresent()) {
                throw new IOException("Workspace metadata filename disagrees with its identity");
            }
            Path relative = Path.of("workspaces", workspace.id() + ".workspace");
            copyFile(source.resolve(relative), target.resolve(relative));
            for (var zone : new ZoneRepository(source).list(workspace.id())) {
                if (!new ZoneRepository(source).read(workspace.id(), zone.id()).filter(zone::equals).isPresent()) {
                    throw new IOException("Zone metadata filename disagrees with its identity");
                }
                relative = Path.of("workspaces", workspace.id().toString(), "zones", zone.id() + ".zone");
                copyFile(source.resolve(relative), target.resolve(relative));
            }
        }
        if (!new ActiveBranchRepository(source).read().equals(new ActiveBranchRepository(target).read())
                || !new ActiveWorkspaceRepository(source).read().equals(new ActiveWorkspaceRepository(target).read())
                || !new WorkingIndexRepository(source).readState().equals(new WorkingIndexRepository(target).readState())
                || !workspaces.equals(new WorkspaceRepository(target).list())) {
            throw new IOException("Converted history metadata differs from source");
        }
        for (var workspace : workspaces) {
            if (!new ZoneRepository(source).list(workspace.id()).equals(new ZoneRepository(target).list(workspace.id()))) {
                throw new IOException("Converted zone metadata differs from source");
            }
        }
    }

    private void copyFile(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;
        if (Files.isSymbolicLink(source)) throw new IOException("History metadata is a symbolic link: " + source);
        AtomicFileWriter.replace(target, RepositoryFileReader.read(source, MAX_FILE_BYTES));
    }
}
