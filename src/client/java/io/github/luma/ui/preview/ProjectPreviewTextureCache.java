package io.github.luma.ui.preview;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.luma.LumaMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class ProjectPreviewTextureCache {

    private static final Map<String, CachedTexture> LOADED_TEXTURES = new HashMap<>();

    private ProjectPreviewTextureCache() {
    }

    public static Identifier load(String projectName, String versionId, Path previewPath) throws IOException {
        String key = cacheKey(projectName, versionId);
        PreviewFileFingerprint fingerprint = PreviewFileFingerprint.read(previewPath);
        CachedTexture existing = LOADED_TEXTURES.get(key);
        if (existing != null && existing.fingerprint().equals(fingerprint)) {
            return existing.textureId();
        }
        if (existing != null) {
            Minecraft.getInstance().getTextureManager().release(existing.textureId());
            LOADED_TEXTURES.remove(key);
        }

        NativeImage image;
        try (var stream = Files.newInputStream(previewPath)) {
            image = NativeImage.read(stream);
        }

        Identifier textureId = Identifier.fromNamespaceAndPath(
                LumaMod.MOD_ID,
                "preview/" + sanitize(projectName) + "/" + sanitize(versionId)
        );
        DynamicTexture texture = new DynamicTexture(() -> "luma-preview-" + key, image);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        LOADED_TEXTURES.put(key, new CachedTexture(textureId, fingerprint));
        return textureId;
    }

    public static void release(String projectName, String versionId) {
        String key = cacheKey(projectName, versionId);
        CachedTexture texture = LOADED_TEXTURES.remove(key);
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(texture.textureId());
        }
    }

    private static String cacheKey(String projectName, String versionId) {
        return sanitize(projectName) + ":" + sanitize(versionId);
    }

    private static String sanitize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9/_-]+", "-");
    }

    private record CachedTexture(Identifier textureId, PreviewFileFingerprint fingerprint) {
    }

    private record PreviewFileFingerprint(
            Path path,
            long size,
            long modifiedAtMillis
    ) {

        private static PreviewFileFingerprint read(Path path) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            return new PreviewFileFingerprint(
                    normalized,
                    Files.size(normalized),
                    Files.getLastModifiedTime(normalized).toMillis()
            );
        }
    }
}
