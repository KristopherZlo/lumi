package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

class BranchShortcutKeyTest {
    @Test
    void mapsLegacyKeyGroupsAndRoundTripsTheirNames() {
        int[][] keys = {
                {48, InputConstants.KEY_0}, {49, InputConstants.KEY_1}, {57, InputConstants.KEY_9},
                {65, InputConstants.KEY_A}, {90, InputConstants.KEY_Z},
                {290, InputConstants.KEY_F1}, {301, InputConstants.KEY_F12},
                {302, InputConstants.KEY_F13}, {313, InputConstants.KEY_F24},
                {320, InputConstants.KEY_NUMPAD0}, {321, InputConstants.KEY_NUMPAD1},
                {329, InputConstants.KEY_NUMPAD9}, {334, InputConstants.KEY_ADD},
                {340, InputConstants.KEY_LSHIFT}, {345, InputConstants.KEY_RCONTROL},
                {39, InputConstants.KEY_APOSTROPHE}, {268, InputConstants.KEY_HOME}
        };
        for (int[] key : keys) {
            assertEquals(key[1], BranchShortcutKey.legacy(key[0]));
            assertEquals(key[1], BranchShortcutKey.decode(BranchShortcutKey.encode(key[1])));
        }
    }
}
