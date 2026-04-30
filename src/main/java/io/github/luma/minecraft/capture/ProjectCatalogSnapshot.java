package io.github.luma.minecraft.capture;

import java.util.List;

record ProjectCatalogSnapshot(
        List<TrackedProject> projects,
        ProjectTrackingIndex<TrackedProject> index
) {
}
