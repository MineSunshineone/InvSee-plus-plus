package com.janboerman.invsee.folia;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.Arrays;

/** Invoked only by the disposable initialized-server acceptance harness. */
public final class InventoryPatchRuntimeChecks {
    private InventoryPatchRuntimeChecks() { }
    public static String run() {
        int checks=0;
        ItemStack diamond=new ItemStack(Material.DIAMOND);
        byte[][] main=new byte[43][];
        ItemStack existing=diamond.clone(); existing.setAmount(60); main[1]=existing.serializeAsBytes();
        byte[][] given=InventoryPatchPlanner.give(main,false,diamond,10);
        check(ItemStack.deserializeBytes(given[1]).getAmount()==64 && ItemStack.deserializeBytes(given[0]).getAmount()==6); checks++;
        check(main[0]==null && ItemStack.deserializeBytes(main[1]).getAmount()==60); checks++;
        ItemStack named=diamond.clone(); var meta=named.getItemMeta(); meta.setDisplayName("Snapshot metadata sentinel"); named.setItemMeta(meta);
        main[0]=named.serializeAsBytes(); given=InventoryPatchPlanner.give(main,false,diamond,1);
        check(ItemStack.deserializeBytes(given[0]).isSimilar(named) && ItemStack.deserializeBytes(given[0]).getAmount()==1); checks++;
        byte[][] copied=InventoryPatchPlanner.cloneContents(main,new byte[43][]);
        check(Arrays.equals(main[0],copied[0]) && main[0]!=copied[0]); checks++;
        byte[][] cleared=InventoryPatchPlanner.clear(main,"DIAMOND",2);
        check(cleared[0]==null && ItemStack.deserializeBytes(cleared[1]).getAmount()==59); checks++;
        main[36]=new ItemStack(Material.DIAMOND_BOOTS).serializeAsBytes();
        check(InventoryPatchPlanner.cloneContents(main,new byte[43][])[36]!=null); checks++;
        check(Arrays.stream(InventoryPatchPlanner.clear(main,null,null)).allMatch(x->x==null)); checks++;
        main[36]=diamond.serializeAsBytes();
        expectFailure(()->InventoryPatchPlanner.cloneContents(main,new byte[43][])); checks++;
        byte[][] full=new byte[27][]; ItemStack stack=diamond.clone(); stack.setAmount(64); for(int i=0;i<27;i++)full[i]=stack.serializeAsBytes();
        expectFailure(()->InventoryPatchPlanner.give(full,true,diamond,1)); checks++;
        check(ItemStack.deserializeBytes(InventoryPatchPlanner.give(new byte[27][],true,diamond,65)[1]).getAmount()==1); checks++;
        return "PASS: " + checks + " inventory patch planner runtime assertions";
    }
    private static void check(boolean valid) { if(!valid) throw new AssertionError("Planner runtime check failed"); }
    private static void expectFailure(Runnable action) { try {action.run();} catch(IllegalArgumentException expected){return;} throw new AssertionError("Expected planner refusal"); }
}
