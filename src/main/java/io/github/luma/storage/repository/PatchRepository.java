package io.github.luma.storage.repository;

import com.google.gson.reflect.TypeToken;
import io.github.luma.domain.model.BlockChangeRecord;
import io.github.luma.domain.model.BlockPatch;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

public final class PatchRepository {

    private static final java.lang.reflect.Type PATCH_FILE_TYPE = new TypeToken<PatchFile>() { }.getType();

    public void save(ProjectLayout layout, BlockPatch patch, List<BlockChangeRecord> changes) throws IOException {
        Files.createDirectories(layout.patchesDir());

        try (var output = new OutputStreamWriter(
                new LZ4FrameOutputStream(new BufferedOutputStream(Files.newOutputStream(layout.patchFile(patch.id())))),
                StandardCharsets.UTF_8
        )) {
            GsonProvider.gson().toJson(new PatchFile(patch, changes), PATCH_FILE_TYPE, output);
        }
    }

    public BlockPatch loadPatch(ProjectLayout layout, String patchId) throws IOException {
        return this.load(layout, patchId).patch();
    }

    public List<BlockChangeRecord> loadChanges(ProjectLayout layout, String patchId) throws IOException {
        return this.load(layout, patchId).changes();
    }

    private PatchFile load(ProjectLayout layout, String patchId) throws IOException {
        try (var input = new InputStreamReader(
                new LZ4FrameInputStream(new BufferedInputStream(Files.newInputStream(layout.patchFile(patchId)))),
                StandardCharsets.UTF_8
        )) {
            PatchFile patchFile = GsonProvider.gson().fromJson(input, PATCH_FILE_TYPE);
            if (patchFile == null) {
                throw new IOException("Patch file is empty: " + patchId);
            }
            return patchFile;
        }
    }

    private record PatchFile(BlockPatch patch, List<BlockChangeRecord> changes) {
    }
}
