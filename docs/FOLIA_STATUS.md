# Folia 26.2+ safety work — not a supported release

The requested baseline is **Folia 26.2**. Later versions require separate adapter
verification; version numbers alone do not establish compatibility. The legacy
plugin descriptor deliberately remains `folia-supported: false`.

## Implemented and checked

- Atomic weak-value spectator cache: distinct equal UUID objects cannot evict a
  live inventory, concurrent cache publication chooses a canonical instance, and
  old reference-queue entries cannot evict newer replacements.
- In-flight future cleanup removes only its own map entry, not a newer request.
- Value-producing scheduler operations complete exceptionally on retirement or
  scheduler rejection rather than remaining pending indefinitely.
- Folia dispatch handles the scheduler's already-retired `null` return and
  arbitrates the run/retire paths once. Global zero-tick delays normalize to one.
- Asynchronous GUI opening is dispatched to the spectator's owner scheduler and
  checks disconnection rather than opening from the target/lookup completion thread.

The Common reactor compiled and passed 25 tests (14 Utils, 11 Common) using
JDK 25 and Maven 3.9.11. This is **not** a full plugin build or a Folia runtime test.
The eight new focused tests cover cache publication/replacement and scheduler
completion/rejection; existing tests cover other utilities and templates.

## Release-blocking architectural work

1. `MainNmsInventory` and `EnderNmsInventory` retain live target inventory lists.
   A viewer in another region mutates/reads those lists from the wrong owner.
   Scheduling the initial open does not make later native menu ticks/clicks safe.
2. Legacy join listeners replace shared references and operate on viewers while
   running on the joining target's thread. They must not run in a detached Folia
   adapter.
3. Offline persistence loads and rewrites whole fake-player data without a
   per-UUID MAIN+ENDER transaction and login/load/save fence. A UUID queue by
   itself cannot synchronize with the server's independent player login I/O.
4. Offline unknown-player creation calls `getHighestBlockAt` from a global
   scheduler fallback, not the owning world region.
5. Addon clone/give/clear paths, permission refresh, viewer lists, shutdown, and
   every public API entry point need their own owner/lifecycle audit.

## Required design and acceptance tests

Use detached per-viewer inventories. Cancel native cross-inventory edits and
process supported actions through an explicit transaction: reserve viewer source
on the viewer owner, validate and commit the target change on the target owner,
then finalize viewer delivery. A failed comparison must never leave items already
moved by vanilla in the viewer's cursor or bottom inventory. Close, disconnect,
retirement and restart after target commit require a retained recovery record.
Never block a region on another region's future. A lock is not region ownership.

Offline editing must serialize inventory kinds and persistence for one UUID,
coordinate login before disk data is consumed, preserve unrelated NBT, and use a
recoverable atomic-write protocol. Failed persistence must remain observable and
recoverable, not be reported as success.

Before enabling the descriptor, run a real Folia 26.2 integration matrix with at
least two separated regions: simultaneous viewers; left/right/shift/double clicks;
drag; hotbar/offhand swap; cursor/crafting/armor; creative and drop actions;
target/viewer teleport, logout and reconnect at each transaction phase; concurrent
MAIN/ENDER edits and login; permission revocation; disk failure; shutdown and
restart recovery. Check exact item conservation and all server thread-check logs.
Later versions need their own artifact build and this matrix, not an unbounded
"26.2 and all future versions" promise.
