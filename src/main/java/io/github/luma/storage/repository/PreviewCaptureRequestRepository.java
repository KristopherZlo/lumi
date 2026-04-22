package io.github.luma.storage.repository;

import io.github.luma.domain.model.PreviewCaptureRequest;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PreviewCaptureRequestRepository {

    public void save(ProjectLayout layout, PreviewCaptureRequest request) throws IOException {
        Files.createDirectories(layout.previewRequestsDir());
        StorageIo.writeAtomically(layout.previewRequestFile(request.versionId()), output -> output.write(
                GsonProvider.gson().toJson(request).getBytes(StandardCharsets.UTF_8)
        ));
    }

    public Optional<PreviewCaptureRequest> load(ProjectLayout layout, String versionId) throws IOException {
        Path file = layout.previewRequestFile(versionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        return Optional.of(GsonProvider.gson().fromJson(Files.readString(file), PreviewCaptureRequest.class));
    }

    public List<PreviewCaptureRequest> loadAll(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.previewRequestsDir())) {
            return List.of();
        }

        List<PreviewCaptureRequest> requests = new ArrayList<>();
        try (var stream = Files.list(layout.previewRequestsDir())) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                requests.add(GsonProvider.gson().fromJson(Files.readString(file), PreviewCaptureRequest.class));
            }
        }

        requests.sort(Comparator.comparing(PreviewCaptureRequest::requestedAt));
        return List.copyOf(requests);
    }

    public void delete(ProjectLayout layout, String versionId) throws IOException {
        Files.deleteIfExists(layout.previewRequestFile(versionId));
    }
}
