package io.github.luma.integration.common;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;

public final class ExternalToolIntegrationRegistry {

    private static final String WORLDEDIT_MOD_ID = "worldedit";
    private static final String FAWE_MOD_ID = "fastasyncworldedit";
    private static final String AXIOM_MOD_ID = "axiom";
    private static final String AXIOM_CLIENT_API_MOD_ID = "axiomclientapi";

    private final Predicate<String> modLoaded;
    private final Predicate<String> classPresent;

    public ExternalToolIntegrationRegistry() {
        this(
                modId -> FabricLoader.getInstance().isModLoaded(modId),
                ExternalToolIntegrationRegistry::classExists
        );
    }

    public ExternalToolIntegrationRegistry(Predicate<String> modLoaded, Predicate<String> classPresent) {
        this.modLoaded = modLoaded;
        this.classPresent = classPresent;
    }

    public List<IntegrationStatus> statuses() {
        return List.of(
                this.worldEditStatus(),
                this.faweStatus(),
                this.axiomStatus(),
                this.fallbackStatus()
        );
    }

    public IntegrationStatus worldEditStatus() {
        boolean modDetected = this.modLoaded.test(WORLDEDIT_MOD_ID);
        boolean corePresent = this.anyClassPresent(
                "com.sk89q.worldedit.WorldEdit",
                "com.sk89q.worldedit.fabric.FabricWorldEdit"
        );
        boolean editSessionEventPresent = this.classPresent.test("com.sk89q.worldedit.event.extent.EditSessionEvent");
        boolean localSessionPresent = this.classPresent.test("com.sk89q.worldedit.LocalSession");
        boolean clipboardPresent = this.anyClassPresent(
                "com.sk89q.worldedit.extent.clipboard.Clipboard",
                "com.sk89q.worldedit.session.ClipboardHolder"
        );
        boolean schematicPresent = this.anyClassPresent(
                "com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat",
                "com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats"
        );

        Set<IntegrationCapability> capabilities = EnumSet.noneOf(IntegrationCapability.class);
        if (editSessionEventPresent) {
            capabilities.add(IntegrationCapability.OPERATION_TRACKING);
            capabilities.add(IntegrationCapability.MASS_EDIT_GROUPING);
        }
        if (localSessionPresent) {
            capabilities.add(IntegrationCapability.SELECTION);
        }
        if (clipboardPresent) {
            capabilities.add(IntegrationCapability.CLIPBOARD);
        }
        if (schematicPresent) {
            capabilities.add(IntegrationCapability.SCHEMATIC);
        }

        boolean available = modDetected || corePresent || !capabilities.isEmpty();
        IntegrationMode mode = this.worldEditMode(available, editSessionEventPresent, corePresent);
        return new IntegrationStatus(WORLDEDIT_MOD_ID, available, List.copyOf(capabilities), mode);
    }

    public IntegrationStatus faweStatus() {
        boolean modDetected = this.modLoaded.test(FAWE_MOD_ID) || this.modLoaded.test("fawe");
        boolean corePresent = this.anyClassPresent(
                "com.fastasyncworldedit.core.Fawe",
                "com.fastasyncworldedit.core.FaweAPI",
                "com.boydti.fawe.Fawe",
                "com.boydti.fawe.FaweAPI"
        );

        boolean available = modDetected || corePresent;
        Set<IntegrationCapability> capabilities = EnumSet.noneOf(IntegrationCapability.class);
        if (available) {
            capabilities.add(IntegrationCapability.FALLBACK_CAPTURE);
            capabilities.add(IntegrationCapability.MASS_EDIT_GROUPING);
        }

        return new IntegrationStatus(
                FAWE_MOD_ID,
                available,
                List.copyOf(capabilities),
                available ? IntegrationMode.DETECTED : IntegrationMode.UNAVAILABLE
        );
    }

    public IntegrationStatus axiomStatus() {
        boolean modDetected = this.modLoaded.test(AXIOM_MOD_ID) || this.modLoaded.test(AXIOM_CLIENT_API_MOD_ID);
        boolean apiPresent = this.anyClassPresent(
                "com.moulberry.axiomclientapi.AxiomClientAPI",
                "com.moulberry.axiomclientapi.service.ToolRegistryService",
                "com.moulberry.axiomclientapi.CustomTool"
        );

        Set<IntegrationCapability> capabilities = EnumSet.noneOf(IntegrationCapability.class);
        if (apiPresent) {
            capabilities.add(IntegrationCapability.CUSTOM_REGION_API);
        }

        boolean available = modDetected || apiPresent;
        IntegrationMode mode = available
                ? capabilities.isEmpty() ? IntegrationMode.DETECTED : IntegrationMode.PARTIAL
                : IntegrationMode.UNAVAILABLE;
        return new IntegrationStatus(AXIOM_MOD_ID, available, List.copyOf(capabilities), mode);
    }

    public IntegrationStatus fallbackStatus() {
        return new IntegrationStatus(
                "fallback",
                true,
                List.of(
                        IntegrationCapability.WORLD_TRACKING,
                        IntegrationCapability.MASS_EDIT_GROUPING,
                        IntegrationCapability.FALLBACK_CAPTURE
                ),
                IntegrationMode.FALLBACK
        );
    }

    private IntegrationMode worldEditMode(boolean available, boolean editSessionEventPresent, boolean corePresent) {
        if (!available) {
            return IntegrationMode.UNAVAILABLE;
        }
        if (editSessionEventPresent && corePresent) {
            return IntegrationMode.ACTIVE;
        }
        return IntegrationMode.DETECTED;
    }

    private boolean anyClassPresent(String... classNames) {
        for (String className : classNames) {
            if (this.classPresent.test(className)) {
                return true;
            }
        }
        return false;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, ExternalToolIntegrationRegistry.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (LinkageError ignored) {
            return false;
        }
    }
}
