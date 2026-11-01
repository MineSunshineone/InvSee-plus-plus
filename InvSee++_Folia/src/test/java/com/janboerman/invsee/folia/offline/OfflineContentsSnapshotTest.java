package com.janboerman.invsee.folia.offline;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class OfflineContentsSnapshotTest {
    private static byte[][] copy(boolean ender, byte[][] contents) throws Exception {
        var method = OfflineStore.class.getDeclaredMethod("copyContents", boolean.class, byte[][].class);
        method.setAccessible(true);
        return (byte[][]) method.invoke(null, ender, contents);
    }
    @Test void batchSnapshotDeepCopiesEveryOccupiedSlot() throws Exception {
        byte[][] original = new byte[43][];
        original[0] = new byte[] {1, 2}; original[42] = new byte[] {3};
        byte[][] copy = copy(false, original);
        original[0][0] = 9; original[42] = null;
        assertArrayEquals(new byte[] {1, 2}, copy[0]);
        assertArrayEquals(new byte[] {3}, copy[42]);
        assertNull(copy[1]);
    }
    @Test void exactManagedSlotCountsAreRequired() throws Exception {
        assertEquals(27, copy(true, new byte[27][]).length);
        for (byte[][] invalid : new byte[][][] {null, new byte[0][], new byte[42][], new byte[44][]}) {
            var thrown = assertThrows(InvocationTargetException.class, () -> copy(false, invalid));
            assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        }
        assertThrows(InvocationTargetException.class, () -> copy(true, new byte[43][]));
    }
}
