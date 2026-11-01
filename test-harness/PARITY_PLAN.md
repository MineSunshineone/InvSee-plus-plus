# Fresh-world administrative parity regression

Reconstructed tests after workspace replacement. Earlier transient logs/counts are not reused. Wait for the build owner's disabled-telemetry and exact-artifact READY confirmation before sending commands.

## Fixtures

Three real localhost clients, actual Folia owner regions verified separately. Fresh main/ender inventories initially empty. Seed target64 ordinary diamonds plus3 named/lore/PDC diamonds in empty slot9, and one boots item in empty feet slot. Verify saved baseline target67/viewerA0/viewerB0; never infer it without inspection. Give and clone intentionally create items, clear intentionally destroys them, so track each delta.

## Scenarios

- Online clone target→A: compare saved Inventory by slot and equipment, retaining exact component/PDC/lore values. A67.
- Give A100, clear A20: A167 then147; target67/B0 unchanged.
- Capacity overflow, unsupported component/NBT syntax and trailing args: explicit refusal, unchanged saved inventories.
- Quiet phaseguard waits for journal access after initial authorization, revokes give permission on actor owner, then releases bounded delay. Expect CONFLICT/no mutation. Restore permission and verify applied before next command. Repeat offline required-bypass permission, without revoking basic command permission.
- Offline target clear2 gives target65; offline source clone→B gives B65 with same metadata/equipment. Main give and ender give/clear/clone are tested separately.
- Ender target give70, clear6, clone→B, then full ender clear B: main/equipment unchanged. Full main clear offline target removes equipment but preserves target ender64.
- Rejoin target: main0 and ender64. Clone B→target restores65 without altering ender; full clear A removes all equipment/items, then full ender clear target leaves both main counts65.
- Concurrent give1 from two actors: item delta equals number of COMPLETED responses. Busy/conflict must not add items.
- Read every persisted patch with the production checksummed journal reader, record terminal states and unresolved count. Save exact canonical/acceptance jar hashes, server version, test logs and snapshots.

Stationary translated clients do not establish native26.3 client or movement compatibility. Live command races validate admission/settled snapshots, not every external plugin mutation. Crash/power-loss safety and sustained-load performance remain separate limits.
