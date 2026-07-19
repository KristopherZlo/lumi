package io.github.lumi.client;

import io.github.lumi.storage.packageformat.LumiPackageDirectory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;

/** Local package-folder access available only to an integrated client. */
public final class ClientPackageAccess {
    private final LumiPackageDirectory packages;

    public ClientPackageAccess(Path worldRoot) {
        packages = new LumiPackageDirectory(worldRoot);
    }

    public static Optional<ClientPackageAccess> integrated() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? Optional.empty() : Optional.of(
                new ClientPackageAccess(server.getWorldPath(LevelResource.ROOT)));
    }

    public List<LumiPackageDirectory.Entry> list() throws IOException {
        return packages.list();
    }

    public void openFolder() throws IOException {
        Util.getPlatform().openFile(packages.ensureDirectory().toFile());
    }
}
