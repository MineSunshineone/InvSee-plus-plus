package com.janboerman.invsee.folia;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class InventoryPatchPlannerTest {
    record Stack(String material, String metadata, int count, int maximum) { }
    static final InventoryPatchPlanner.StackOps<Stack> OPS = new InventoryPatchPlanner.StackOps<>() {
        public Stack decode(byte[] bytes) { String[] p = new String(bytes, StandardCharsets.UTF_8).split(":"); return new Stack(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3])); }
        public byte[] encode(Stack s) { return (s.material+":"+s.metadata+":"+s.count+":"+s.maximum).getBytes(StandardCharsets.UTF_8); }
        public Stack withAmount(Stack s, int n) { return new Stack(s.material,s.metadata,n,s.maximum); }
        public int amount(Stack s) { return s.count; }
        public int maximum(Stack s) { return s.maximum; }
        public boolean similar(Stack a, Stack b) { return a.material.equals(b.material) && a.metadata.equals(b.metadata) && a.maximum == b.maximum; }
        public String material(Stack s) { return s.material; }
        public boolean accepts(int slot, Stack s) { return slot < 36 || slot == 40 || slot == 36 && s.material.equals("BOOTS") && s.count == 1; }
    };
    static Stack stone(int n) { return new Stack("STONE", "plain", n, 64); }
    static byte[] bytes(Stack s) { return OPS.encode(s); }
    static int amount(byte[] b) { return b == null ? 0 : OPS.decode(b).count; }

    @Test void giveMergesBeforeEmptyAndDoesNotMutateInputs() {
        byte[][] before = new byte[43][]; before[1] = bytes(stone(60));
        byte[] saved = before[1].clone();
        byte[][] after = InventoryPatchPlanner.give(before,false,stone(1),10,OPS);
        assertEquals(64,amount(after[1])); assertEquals(6,amount(after[0])); assertArrayEquals(saved,before[1]); assertNull(before[0]);
    }
    @Test void giveKeepsDifferentMetadataSeparate() {
        byte[][] before = new byte[43][]; before[0] = bytes(new Stack("STONE","custom",10,64));
        byte[][] after = InventoryPatchPlanner.give(before,false,stone(1),2,OPS);
        assertEquals(10,amount(after[0])); assertEquals(2,amount(after[1]));
    }
    @Test void giveInsufficientCapacityDoesNotUseEquipmentOrReturnPartial() {
        byte[][] before = new byte[43][]; for(int i=0;i<36;i++) before[i]=bytes(stone(64)); before[0]=bytes(stone(63));
        assertThrows(IllegalArgumentException.class,()->InventoryPatchPlanner.give(before,false,stone(1),2,OPS));
        assertEquals(63,amount(before[0])); assertNull(before[40]);
    }
    @Test void giveHonorsCustomMaximumAndEnderStorage() {
        byte[][] before=new byte[27][]; Stack small=new Stack("STONE","custom",1,16);
        byte[][] after=InventoryPatchPlanner.give(before,true,small,33,OPS);
        assertEquals(16,amount(after[0])); assertEquals(16,amount(after[1])); assertEquals(1,amount(after[2]));
    }
    @Test void giveRejectsBadShapeKindAndAmount() {
        assertThrows(IllegalArgumentException.class,()->InventoryPatchPlanner.give(new byte[41][],false,stone(1),1,OPS));
        assertThrows(IllegalArgumentException.class,()->InventoryPatchPlanner.give(new byte[43][],true,stone(1),1,OPS));
        assertThrows(IllegalArgumentException.class,()->InventoryPatchPlanner.give(new byte[43][],false,stone(1),0,OPS));
    }
    @Test void clearFiltersExactlyAndRemovesRequestedCount() {
        byte[][] before=new byte[43][]; before[0]=bytes(stone(5)); before[1]=bytes(new Stack("DIAMOND","plain",2,64)); before[2]=bytes(stone(7));
        byte[][] after=InventoryPatchPlanner.clear(before,"STONE",8,OPS);
        assertNull(after[0]); assertEquals(2,amount(after[1])); assertEquals(4,amount(after[2])); assertEquals(5,amount(before[0]));
    }
    @Test void clearAllIncludesEquipment() {
        byte[][] before=new byte[43][]; before[42]=bytes(new Stack("SADDLE","custom",1,1)); before[0]=bytes(stone(64));
        assertTrue(Arrays.stream(InventoryPatchPlanner.clear(before,null,null,OPS)).allMatch(x->x==null));
    }
    @Test void clearZeroCopiesAndNegativeRejects() {
        byte[][] before=new byte[27][]; before[0]=bytes(stone(4));
        byte[][] after=InventoryPatchPlanner.clear(before,null,0,OPS); after[0][0]='X';
        assertEquals("STONE",OPS.decode(before[0]).material);
        assertThrows(IllegalArgumentException.class,()->InventoryPatchPlanner.clear(before,null,-1,OPS));
    }
    @Test void cloneDeepCopiesMetadataAndValidEquipment() {
        byte[][] before=new byte[43][]; before[0]=bytes(new Stack("STONE","custom",2,64)); before[36]=bytes(new Stack("BOOTS","custom",1,1));
        byte[][] after=InventoryPatchPlanner.cloneContents(before,new byte[43][],OPS);
        assertArrayEquals(before[36],after[36]); assertNotSame(before[36],after[36]); after[0][0]='X'; assertEquals("STONE",OPS.decode(before[0]).material);
    }
    @Test void cloneRejectsInvalidEquipmentOverstackAndShape() {
        byte[][] before=new byte[43][]; before[36]=bytes(stone(1));
        assertThrows(IllegalArgumentException.class,()->InventoryPatchPlanner.cloneContents(before,new byte[43][],OPS));
        before[36]=null; before[0]=bytes(stone(65));
        assertThrows(IllegalArgumentException.class,()->InventoryPatchPlanner.cloneContents(before,new byte[43][],OPS));
        assertThrows(IllegalArgumentException.class,()->InventoryPatchPlanner.cloneContents(new byte[27][],new byte[43][],OPS));
    }
}
