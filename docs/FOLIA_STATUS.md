# Legacy hardening checkpoint and standalone Folia implementation


## Current standalone release candidate

`InvSee++_Folia` is the separate Folia implementation; it does **not** execute the
legacy shared NMS mirror/API paths below. The release candidate is
`0.31.23-folia.1-rc2` (`InvSee++-Folia.jar`), for **Java 25 and the explicit
Minecraft/Folia 26.2 / 26.3 allowlist**. New versions require separate verification,
not merely an edited version check. The legacy plugin remains disabled for Folia.

The standalone module implements owner-dispatched detached menus with native
mutation protection, journaled slot transfers and give/clear/clone patches,
UUID-wide offline login fencing, checked persistence, and recoverable escrow.
Online and offline main/ender inventories are supported; ambiguous persistence
outcomes are quarantined rather than blindly replayed.

### RC2 login-priority behavior

Normal offline editing no longer rejects a player's login merely because an
administrator has the inspector open. Login immediately reserves admission,
invalidates the offline inspector, and closes it on the administrator's owner
thread. Already-admitted protocols retain a pin through commit, rollback and
acknowledgement. The asynchronous authentication worker waits for their drain
before the server reads player data; region threads do not wait for login.
The administrator can reopen after the player joins and edit concurrently through
owner-thread compare-and-set validation. A stale edit conflicts instead of
overwriting the player's newer changes.

The synchronization deadline is 15 seconds. Timeout, shutdown, duplicate login or
unresolved persistence recovery can still fail closed with a distinct error;
this is not the old normal-edit-busy rejection policy. A racing admin operation
may be refused or conflict. The view is not automatically reopened after join.

### Verified RC2 scope (2026-11-01)

- **108 tests pass** against each official Folia **26.2 build 7 BETA** and
  **26.3 build 6 ALPHA** classpath.
- The same 26.2-compiled candidate passed actual-client tests on both versions:
  idle main/ender inspector first-attempt login, inspector closure, selected-source
  invalidation and stale-packet rejection, login during a pinned administrative
  operation, safe conflict for a delayed GUI operation, online reopening and
  metadata-preserving transfer, and conflict against an actual target-owner edit.
- Initialized storage probes on both versions exercise a queued CAS, login wait,
  and rollback/acknowledgement while login has priority. Artificial journal holds
  test ordering, not natural latency or a sustained-load benchmark.
- Clients used official ViaVersion/ViaBackwards translation from 26.1, not native
  26.2/26.3 clients. See
  [`RC2 login results`](../test-harness/RC2_LOGIN_RESULTS.md) for exact evidence and
  final-package binding. Earlier broad administrative/GUI coverage remains
  documented in [`RC1 parity results`](../test-harness/PARITY_RESULTS.md); those
  historical runs are not relabeled as RC2 execution.

Final RC2 jar SHA-256:
`e769c90cd1f6d5f6685bda05110407fc0638ff1d13710580fb04be37f5a3b450`.
All 65 production classes match the tested candidate. The exact packaged jar
passed first-attempt login, offline-view closure and online-view reopening smokes
on both versions, with graceful shutdown. Full suites were not repeated after
metadata-only packaging.

The new module's `folia-supported: true` gate is enabled; the legacy gate stays
false. The standalone support flag does not make the legacy plugin or addons safe.

### Explicit boundaries

Unsupported actions are refused, not routed to vanilla editing: arbitrary
occupied-slot swaps, drag/drop/creative cloning, and cursor/crafting editing.
Give accepts material/count, not custom component/NBT syntax. This module is not
the old public API or a per-world-inventory integration. Offline data must already
exist in the current data version; older data must first migrate through login.

No suite proves arbitrary crash/power-loss safety, every third-party inventory
race, native-client compatibility, sustained-load performance, or all future
versions. Fsync and checked saves have real latency costs; bounded queues and
coalesced refreshes are not a throughput guarantee. See
[`InvSee++_Folia/README.zh-CN.md`](../InvSee++_Folia/README.zh-CN.md) for installation,
permissions, recovery procedures and the full feature boundaries.

## Historical legacy-code checkpoint

The remaining sections describe the **earlier legacy implementation**, not an
outstanding implementation plan for the separate standalone module. Its
`folia-supported: false` descriptor remains intentional.

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

## Historical legacy release blockers

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

## Historical design and acceptance requirements

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
