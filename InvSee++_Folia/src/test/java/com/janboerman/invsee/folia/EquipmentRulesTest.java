package com.janboerman.invsee.folia;

import net.minecraft.world.entity.EquipmentSlot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests policy/mapping; real component extraction additionally needs an initialized server. */
class EquipmentRulesTest {
    @Test void mapsAllSevenEquipmentSlots() {
        EquipmentSlot[] expected = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST,
                EquipmentSlot.HEAD, EquipmentSlot.OFFHAND, EquipmentSlot.BODY, EquipmentSlot.SADDLE};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], EquipmentRules.slot(i + 36));
    }
    @Test void acceptsCorrectComponentsNotMaterialNames() {
        assertEquals(1, EquipmentRules.componentCapacity(36, 64, EquipmentSlot.FEET, true));
        assertEquals(1, EquipmentRules.componentCapacity(39, 64, EquipmentSlot.HEAD, true));
    }
    @Test void rejectsWrongComponentSlot() {
        assertEquals(0, EquipmentRules.componentCapacity(36, 1, EquipmentSlot.HEAD, true));
    }
    @Test void rejectsAbsentEquippableComponent() {
        assertEquals(0, EquipmentRules.componentCapacity(39, 64, null, true));
    }
    @Test void rejectsEntityRestrictedAnimalEquipment() {
        assertEquals(0, EquipmentRules.componentCapacity(41, 1, EquipmentSlot.BODY, false));
        assertEquals(0, EquipmentRules.componentCapacity(42, 1, EquipmentSlot.SADDLE, false));
    }
    @Test void permitsCustomPlayerBodyAndSaddleComponentsWithSingleItemLimit() {
        assertEquals(1, EquipmentRules.componentCapacity(41, 64, EquipmentSlot.BODY, true));
        assertEquals(1, EquipmentRules.componentCapacity(42, 64, EquipmentSlot.SADDLE, true));
    }
    @Test void offhandAndStorageRetainOrdinaryItemMaximum() {
        assertEquals(64, EquipmentRules.componentCapacity(40, 64, null, false));
        assertEquals(16, EquipmentRules.componentCapacity(35, 16, null, false));
    }
    @Test void refusesInvalidIndicesAndAllowsClearingValidEquipment() {
        assertEquals(0, EquipmentRules.componentCapacity(43, 64, null, true));
        assertEquals(0, EquipmentRules.componentCapacity(-1, 64, null, true));
        assertFalse(EquipmentRules.accepts(43, null));
        assertTrue(EquipmentRules.accepts(41, null));
    }
}
