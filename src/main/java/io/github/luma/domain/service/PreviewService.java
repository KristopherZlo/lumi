package io.github.luma.domain.service;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.storage.ProjectLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

public final class PreviewService {

    private final PreviewColumnSampler columnSampler = new PreviewColumnSampler();
    private final IsometricPreviewRenderer renderer = new IsometricPreviewRenderer();

    public PreviewInfo capture(ProjectLayout layout, String versionId, Bounds3i bounds, ServerLevel level) throws IOException {
        return this.write(layout, versionId, this.sample(bounds, level));
    }

    public PreviewRenderData sample(Bounds3i bounds, ServerLevel level) {
        return this.sample(bounds, (BlockGetter) level);
    }

    PreviewRenderData sample(Bounds3i bounds, BlockGetter blocks) {
        PreviewScene scene = this.columnSampler.sample(bounds, blocks);
        return this.renderer.render(scene, blocks);
    }

    public PreviewInfo write(ProjectLayout layout, String versionId, PreviewRenderData renderData) throws IOException {
        Files.createDirectories(layout.previewsDir());

        BufferedImage image = new BufferedImage(renderData.width(), renderData.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, renderData.width(), renderData.height(), renderData.pixels(), 0, renderData.width());

        ImageIO.write(image, "png", layout.previewFile(versionId).toFile());
        return new PreviewInfo(layout.previewFile(versionId).getFileName().toString(), renderData.width(), renderData.height());
    }

    public record PreviewRenderData(int width, int height, int[] pixels) {
    }
}
