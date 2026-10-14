package io.github.lumi.buildconfig;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class LegacyGameTestAnnotationRepairTest {
    @Test
    void repairsTheEnumNameWithoutChangingTheClassBodyAndRefusesUnknownInputs() throws Exception {
        var original = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(original)) {
            data.writeInt(0xCAFEBABE);
            data.writeInt(65);
            data.writeShort(2);
            data.writeByte(1);
            data.writeUTF("field_11467");
            data.write(new byte[] {1, 2, 3});
        }
        var repair = new LegacyGameTestAnnotationRepair();
        byte[] fixed = repair.repairClass(original.toByteArray());
        try (var data = new DataInputStream(new ByteArrayInputStream(fixed))) {
            data.skipNBytes(11);
            assertEquals("NONE", data.readUTF());
            assertArrayEquals(new byte[] {1, 2, 3}, data.readAllBytes());
        }
        assertThrows(IOException.class, () -> repair.repairClass(fixed));
        assertThrows(IOException.class, () -> repair.repairClass(new byte[] {0}));
    }
}
