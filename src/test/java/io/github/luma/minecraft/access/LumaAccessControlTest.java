package io.github.luma.minecraft.access;

import io.github.luma.domain.model.ProjectSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumaAccessControlTest {

    private final LumaAccessControl accessControl = LumaAccessControl.getInstance();

    @Test
    void survivalModeRequiresProjectSettingAndPermission() {
        ProjectSettings survivalAllowed = ProjectSettings.sanitize(new ProjectSettings(
                false,
                10,
                5,
                10,
                0.20D,
                true,
                true,
                false,
                false,
                512,
                true,
                false,
                true
        ));

        assertFalse(this.accessControl.canUse(ProjectSettings.defaults(), true, true));
        assertFalse(this.accessControl.canUse(survivalAllowed, true, false));
        assertTrue(this.accessControl.canUse(survivalAllowed, true, true));
        assertTrue(this.accessControl.canUse(ProjectSettings.defaults(), false, true));
    }
}
