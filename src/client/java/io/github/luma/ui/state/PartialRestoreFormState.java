package io.github.luma.ui.state;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestorePlanSummary;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public final class PartialRestoreFormState {

    private String minX = "";
    private String minY = "";
    private String minZ = "";
    private String maxX = "";
    private String maxY = "";
    private String maxZ = "";
    private PartialRestorePlanSummary summary;

    public String minX() {
        return this.minX;
    }

    public void setMinX(String minX) {
        this.minX = minX;
        this.summary = null;
    }

    public String minY() {
        return this.minY;
    }

    public void setMinY(String minY) {
        this.minY = minY;
        this.summary = null;
    }

    public String minZ() {
        return this.minZ;
    }

    public void setMinZ(String minZ) {
        this.minZ = minZ;
        this.summary = null;
    }

    public String maxX() {
        return this.maxX;
    }

    public void setMaxX(String maxX) {
        this.maxX = maxX;
        this.summary = null;
    }

    public String maxY() {
        return this.maxY;
    }

    public void setMaxY(String maxY) {
        this.maxY = maxY;
        this.summary = null;
    }

    public String maxZ() {
        return this.maxZ;
    }

    public void setMaxZ(String maxZ) {
        this.maxZ = maxZ;
        this.summary = null;
    }

    public PartialRestorePlanSummary summary() {
        return this.summary;
    }

    public void setSummary(PartialRestorePlanSummary summary) {
        this.summary = summary;
    }

    public void ensureDefaults(Bounds3i projectBounds, Bounds3i fallbackBounds) {
        if (!this.minX.isBlank()
                || !this.minY.isBlank()
                || !this.minZ.isBlank()
                || !this.maxX.isBlank()
                || !this.maxY.isBlank()
                || !this.maxZ.isBlank()) {
            return;
        }

        Bounds3i bounds = projectBounds == null ? fallbackBounds : projectBounds;
        if (bounds == null) {
            return;
        }
        this.minX = Integer.toString(bounds.min().x());
        this.minY = Integer.toString(bounds.min().y());
        this.minZ = Integer.toString(bounds.min().z());
        this.maxX = Integer.toString(bounds.max().x());
        this.maxY = Integer.toString(bounds.max().y());
        this.maxZ = Integer.toString(bounds.max().z());
    }

    public Optional<PartialRestoreRequest> request(String projectName, String versionId, String actor) {
        try {
            Bounds3i bounds = new Bounds3i(
                    new BlockPoint(Integer.parseInt(this.minX), Integer.parseInt(this.minY), Integer.parseInt(this.minZ)),
                    new BlockPoint(Integer.parseInt(this.maxX), Integer.parseInt(this.maxY), Integer.parseInt(this.maxZ))
            );
            Bounds3i normalized = normalize(bounds);
            return Optional.of(new PartialRestoreRequest(
                    projectName,
                    versionId,
                    normalized,
                    PartialRestoreRegionSource.MANUAL_BOUNDS,
                    actor,
                    Map.of()
            ));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public static Bounds3i fallbackAround(BlockPos pos, int minY, int maxY) {
        return new Bounds3i(
                new BlockPoint(pos.getX() - 8, Math.max(minY, pos.getY() - 8), pos.getZ() - 8),
                new BlockPoint(pos.getX() + 8, Math.min(maxY, pos.getY() + 8), pos.getZ() + 8)
        );
    }

    private static Bounds3i normalize(Bounds3i bounds) {
        return new Bounds3i(
                new BlockPoint(
                        Math.min(bounds.min().x(), bounds.max().x()),
                        Math.min(bounds.min().y(), bounds.max().y()),
                        Math.min(bounds.min().z(), bounds.max().z())
                ),
                new BlockPoint(
                        Math.max(bounds.min().x(), bounds.max().x()),
                        Math.max(bounds.min().y(), bounds.max().y()),
                        Math.max(bounds.min().z(), bounds.max().z())
                )
        );
    }
}
