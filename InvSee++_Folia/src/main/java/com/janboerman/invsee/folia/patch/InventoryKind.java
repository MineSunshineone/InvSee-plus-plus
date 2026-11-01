package com.janboerman.invsee.folia.patch;

/** Managed 26.2+ inventory layout, including main equipment slots. */
public enum InventoryKind {
    MAIN(43), ENDER(27);
    private final int slots;
    InventoryKind(int slots) { this.slots = slots; }
    public int slots() { return slots; }
}
