package io.github.luma.client.update;

import java.util.List;

public record UpdateManifest(int schema, String modId, List<UpdateRelease> versions) {

    public UpdateManifest {
        modId = modId == null ? "" : modId.trim();
        versions = versions == null ? List.of() : List.copyOf(versions);
    }
}
