package io.github.luma.client.update;

import java.util.Comparator;

public final class UpdateCandidateSelector {

    private final UpdateVersionComparator versionComparator = new UpdateVersionComparator();

    public UpdateCheckResult select(UpdateManifest manifest, InstalledModInfo installed) {
        if (manifest == null || installed == null) {
            return UpdateCheckResult.unavailable("missing-context");
        }
        return manifest.versions().stream()
                .filter(release -> release != null)
                .filter(release -> release.supportsMinecraft(installed.minecraftVersion()))
                .filter(release -> release.supportsLoader(installed.loader()))
                .filter(UpdateRelease::isStableChannel)
                .filter(release -> this.versionComparator.compare(release.version(), installed.modVersion()) > 0)
                .max(Comparator.comparing(UpdateRelease::version, this.versionComparator::compare))
                .map(UpdateCheckResult::available)
                .orElseGet(UpdateCheckResult::noneAvailable);
    }
}
