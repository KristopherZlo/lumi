package io.github.luma.ui.preview;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.luma.LumaMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class ProjectPreviewTextureCache {

    private static final int MAX_TEXTURES = 64;
    private static final Map<String, CachedTexture> LOADED_TEXTURES = new LinkedHashMap<>(16, 0.75F, true);

    private ProjectPreviewTextureCache() {
    }

    public static synchronized Identifier load(String projectName, String versionId, Path previewPath) throws IOException {
        String key = cacheKey(projectName, versionId);
        PreviewFileFingerprint fingerprint = PreviewFileFingerprint.read(previewPath);
        CachedTexture existing = LOADED_TEXTURES.get(key);
        if (existing != null && existing.fingerprint().equals(fingerprint)) {
            return existing.textureId();
        }
        if (existing != null) {
            releaseTexture(existing);
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
        trimToMax();
        return textureId;
    }

    public static synchronized void release(String projectName, String versionId) {
        String key = cacheKey(projectName, versionId);
        CachedTexture texture = LOADED_TEXTURES.remove(key);
        if (texture != null) {
            releaseTexture(texture);
        }
    }

    public static synchronized void releaseAll() {
        for (CachedTexture texture : new ArrayList<>(LOADED_TEXTURES.values())) {
            releaseTexture(texture);
        }
        LOADED_TEXTURES.clear();
    }

    private static String cacheKey(String projectName, String versionId) {
        return sanitize(projectName) + ":" + sanitize(versionId);
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/_-]+", "-");
    }

    private static void trimToMax() {
        Iterator<Map.Entry<String, CachedTexture>> iterator = LOADED_TEXTURES.entrySet().iterator();
        while (LOADED_TEXTURES.size() > MAX_TEXTURES && iterator.hasNext()) {
            Map.Entry<String, CachedTexture> eldest = iterator.next();
            iterator.remove();
            releaseTexture(eldest.getValue());
        }
    }

    private static void releaseTexture(CachedTexture texture) {
        Minecraft.getInstance().getTextureManager().release(texture.textureId());
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
