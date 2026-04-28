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
    private static final String AXION_MOD_ID = "axion";
    private static final String AUTOBUILD_MOD_ID = "autobuild";
    private static final String SIMPLE_BUILDING_MOD_ID = "simplebuilding";
    private static final String EFFORTLESS_BUILDING_MOD_ID = "effortlessbuilding";
    private static final String LITEMATICA_MOD_ID = "litematica";
    private static final String TWEAKEROO_MOD_ID = "tweakeroo";

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
                this.axionStatus(),
                this.autoBuildStatus(),
                this.simpleBuildingStatus(),
                this.effortlessBuildingStatus(),
                this.litematicaStatus(),
                this.tweakerooStatus(),
                this.fallbackStatus()
        );
    }

    public boolean stackTraceDetectionAvailable() {
        return this.worldEditStatus().available()
                || this.faweStatus().available()
                || this.axiomStatus().available()
                || this.axionStatus().available()
                || this.autoBuildStatus().available()
                || this.simpleBuildingStatus().available()
                || this.effortlessBuildingStatus().available()
                || this.litematicaStatus().available()
                || this.tweakerooStatus().available();
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
            capabilities.add(IntegrationCapability.ENTITY_TRACKING);
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
            capabilities.add(IntegrationCapability.ENTITY_TRACKING);
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
        boolean available = modDetected || apiPresent;
        if (apiPresent) {
            capabilities.add(IntegrationCapability.CUSTOM_REGION_API);
        }
        if (available) {
            capabilities.add(IntegrationCapability.FALLBACK_CAPTURE);
            capabilities.add(IntegrationCapability.ENTITY_TRACKING);
        }

        IntegrationMode mode = available
                ? capabilities.isEmpty() ? IntegrationMode.DETECTED : IntegrationMode.PARTIAL
                : IntegrationMode.UNAVAILABLE;
        return new IntegrationStatus(AXIOM_MOD_ID, available, List.copyOf(capabilities), mode);
    }

    public IntegrationStatus axionStatus() {
        return this.detectedBuilderToolStatus(
                AXION_MOD_ID,
                List.of(AXION_MOD_ID),
                List.of("axion.Axion", "com.moulberry.axion.Axion", "dev.moulberry.axion.Axion"),
                false
        );
    }

    public IntegrationStatus autoBuildStatus() {
        return this.detectedBuilderToolStatus(
                AUTOBUILD_MOD_ID,
                List.of(AUTOBUILD_MOD_ID, "auto-build"),
                List.of("autobuild.AutoBuild", "net.autobuild.AutoBuild"),
                false
        );
    }

    public IntegrationStatus simpleBuildingStatus() {
        return this.detectedBuilderToolStatus(
                SIMPLE_BUILDING_MOD_ID,
                List.of(SIMPLE_BUILDING_MOD_ID, "simple-building"),
                List.of("simplebuilding.SimpleBuilding", "net.simplebuilding.SimpleBuilding"),
                false
        );
    }

    public IntegrationStatus effortlessBuildingStatus() {
        return this.detectedBuilderToolStatus(
                EFFORTLESS_BUILDING_MOD_ID,
                List.of(EFFORTLESS_BUILDING_MOD_ID, "effortless_building"),
                List.of("nl.requios.effortlessbuilding.EffortlessBuilding"),
                false
        );
    }

    public IntegrationStatus litematicaStatus() {
        return this.playerDrivenPlacementStatus(
                LITEMATICA_MOD_ID,
                List.of(LITEMATICA_MOD_ID),
                List.of("fi.dy.masa.litematica.Litematica")
        );
    }

    public IntegrationStatus tweakerooStatus() {
        return this.playerDrivenPlacementStatus(
                TWEAKEROO_MOD_ID,
                List.of(TWEAKEROO_MOD_ID),
                List.of("fi.dy.masa.tweakeroo.Tweakeroo")
        );
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

    private IntegrationStatus detectedBuilderToolStatus(
            String id,
            List<String> modIds,
            List<String> classNames,
            boolean entityTracking
    ) {
        boolean available = modIds.stream().anyMatch(this.modLoaded)
                || classNames.stream().anyMatch(this.classPresent);
        Set<IntegrationCapability> capabilities = EnumSet.noneOf(IntegrationCapability.class);
        if (available) {
            capabilities.add(IntegrationCapability.FALLBACK_CAPTURE);
            capabilities.add(IntegrationCapability.MASS_EDIT_GROUPING);
            if (entityTracking) {
                capabilities.add(IntegrationCapability.ENTITY_TRACKING);
            }
        }
        return new IntegrationStatus(
                id,
                available,
                List.copyOf(capabilities),
                available ? IntegrationMode.DETECTED : IntegrationMode.UNAVAILABLE
        );
    }

    private IntegrationStatus playerDrivenPlacementStatus(
            String id,
            List<String> modIds,
            List<String> classNames
    ) {
        boolean available = modIds.stream().anyMatch(this.modLoaded)
                || classNames.stream().anyMatch(this.classPresent);
        return new IntegrationStatus(
                id,
                available,
                available ? List.of(IntegrationCapability.WORLD_TRACKING) : List.of(),
                available ? IntegrationMode.DETECTED : IntegrationMode.UNAVAILABLE
        );
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
