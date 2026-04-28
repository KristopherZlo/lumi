package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.storage.ProjectLayout;
import java.util.List;

record TrackedProject(ProjectLayout layout, BuildProject project, List<ProjectVariant> variants) {
}
