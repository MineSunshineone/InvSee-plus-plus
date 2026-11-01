# Folia 26.2 offline persistence

The standalone `InvSee++_Folia` adapter does not create fake players or obtain a
world/chunk to edit offline inventories. It reads the existing player's compressed
NBT and replaces only the requested inventory slot. Unknown players and data saved
by a different Minecraft data version are rejected: the player must join normally
once to create/migrate their data. Future Minecraft versions require adapter tests.

## Ownership and admission

`OfflineAccessCoordinator` admits exactly one token per UUID, shared by main and
ender inventory kinds. An offline session retains that token through all accepted
IO, including IO queued when the view closes. Closing a view is not an early unlock.
`Lease.closeAsync()` reports when its accepted IO has drained.

At `AsyncPlayerPreLoginEvent.HIGHEST`, an otherwise allowed connection must reserve
that same UUID before the server can load player data. A busy UUID gets an explicit
retry message. At MONITOR, a subsequently denied admission releases its own token;
a denied duplicate never releases the existing connection's token. Changed UUIDs
at a later HIGHEST listener are denied at MONITOR and release the original token;
a re-allowed denial still has to obtain a token. Mutating events after our MONITOR
callback violates the listener contract and is unsupported. Accepted tokens
remain through the native final save. They are released on
`PlayerConnectionCloseEvent`, **not** `PlayerQuitEvent`.

The exact 26.2 source ordering was checked. Authentication/Velocity dispatches
pre-login before client verification; `PrepareSpawnTask.start` loads player data
only later during configuration:


- Paper [`Connection.java.patch`](https://github.com/PaperMC/Paper/blob/ver/26.2/paper-server/patches/sources/net/minecraft/network/Connection.java.patch):
  disconnect listener runs before connection-close event.
- Paper [`PlayerList.java.patch`](https://github.com/PaperMC/Paper/blob/ver/26.2/paper-server/patches/sources/net/minecraft/server/players/PlayerList.java.patch):
  quit callback and cursor handling precede player save/removal.
- Folia [`Region Threading Base`](https://github.com/PaperMC/Folia/blob/ver/26.2.x/folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch)
  retains that ordering.

Do not hot-reload/re-enable this adapter into a server with pre-existing in-flight
logins. Initial server startup is the supported registration boundary. Shutdown
stops admission and drains **only its own disk workers** before returning, so Bukkit
cannot remove the admission listener while a plugin disk write is still active.
No normal operation blocks a region waiting for another region or disk operation.

## Persistence and recovery

A bounded two-worker IO pool permits at most 128 outstanding operations globally
and 16 per offline lease. Each lease serializes both inventory kinds. Each CAS
reloads current NBT, compares the actual target slot, and retains all unrelated NBT.
The file's original bytes are checked again immediately before atomic installation.
This coordinates native saves, not arbitrary third-party plugins bypassing the
adapter and rewriting the same player file.

`AtomicPlayerDataFile` requires atomic rename and directory fsync support:

1. Create `<uuid>.dat.invsee-transaction` beside the player file.
2. Durably write `before.dat` and `after.dat` and synchronize the directory.
3. Atomically replace the live file with a forced temporary after-image; sync the
   parent directory. There is no unsafe non-atomic fallback.
4. Persist the commit marker. Return CAS success, retaining the journal.
5. Only after the transfer layer durably records its next phase does it call
   `acknowledgeCommit()`. Rename the journal atomically to `.invsee-acknowledged`,
   synchronize, then clean it up. Interrupted acknowledgement cleanup is harmless.

Any error with a pending journal is indeterminate, **not** an invitation to retry
or restore items automatically. The UUID remains blocked for login and offline
edits. Startup discovers these journals before admission. `inspect()` compares the
live bytes to the before/after images without changing any file. Conflicting or
partially prepared records retain evidence. Recovery must reconcile the transfer
ledger and both inventories; blindly copying a before/after image can duplicate or
lose items. Disk journaling alone does not solve a two-player transfer crash.

`verifySavedSlot()` is a separate read-only bridge for owner-thread online saves:
it reads and forces the same existing file descriptor and parent, checks stable
file identity/size/mtime, and verifies the expected slot in those exact bytes.
A missing, stale, malformed, or failed native save fails the transfer; it never
uses an offline writer against an online player. This verifies the observed save,
not the durability of arbitrary future native autosaves.

## Administrative whole-inventory patches

`Lease.compareAndSetContents(ender, expected, replacement)` accepts exactly 43 main
or 27 ender slots and deep-copies all item bytes at admission. It checks every
expected slot before editing the local NBT, then uses one atomic file transaction.
The other inventory kind, unrelated root tags, and unchanged item tags are kept.
A stale comparison returns false without writing a journal. The transfer layer
must still durably record its phase before `acknowledgeCommit()`.

`verifySavedContents` checks all managed slots in the same forced descriptor
snapshot for an owner-thread online save. Both APIs preserve the normal journal
acknowledgement contract, including no-op replacements. A command layer may avoid
no-op patches before it prepares a transaction.

These APIs and their additional tests were reconstructed on 2026-11-01 from the
author's retained implementation history after the workspace rollback. Commit
`9307c134` is the new reconstruction, not a claim of byte-identical restoration of
the lost parity commits. Current revalidation: offline production and tests compile
against the official 26.2 and 26.3 runtimes; all 21 offline-package JUnit tests pass
on each (14 persistence/snapshot tests plus seven name-resolver tests). New
initialized-server tests must be rerun on the reconstructed integrated artifact.
The older runtime evidence below describes the historical baseline, not the new
reconstruction's runtime status.

## Exact current layout

Official Folia 26.2 build 7 bytecode was inspected with `javap`: `Inventory` stores
only slots 0–35; `EnderItems` stores 0–26. Equipment is in the separate `equipment`
compound. Main snapshots use Bukkit's 43-slot mapping:

- 0–35 storage; 36 feet; 37 legs; 38 chest; 39 head; 40 offhand;
- 41 body; 42 saddle.

No obsolete legacy slot-100 armor encoding is written. NMS `ItemStack.CODEC`
validates and compares complete item components using the initialized server
registry. Item bytes use Bukkit's compressed-NBT serialization with DataVersion.
Both null and an empty byte array represent an empty endpoint slot. Cursor and
crafting are not persisted by this adapter.

## Verification evidence

- Twelve focused Java-only tests cover exclusive UUID admission, 200 simultaneous
  login/offline races, stale token release, shutdown admission, atomic before/after
  evidence, stale disk rejection, interrupted preparation/ack cleanup, external
  conflicts, missing/symlink rejection, and deterministic native inode-swap
  durability verification.
- Offline sources compile against the exact official Folia 26.2 runtime.
- Initialized-server acceptance checks passed on 2026-10-31 at 15:52:25 UTC:
  component/name/lore/enchantment/PDC roundtrips, 43-slot equipment mapping,
  main/ender edits and unrelated NBT preservation, stale/empty comparisons,
  malformed/duplicate/version rejection, actual lease/CAS/ack persistence and
  manual admission/cancel/duplicate/connection-close callbacks.
- The next initialized-server run at 15:55:37 UTC also passed deterministic
  saturation of both IO workers, the per-lease bound, and login fencing while a
  closed lease drains, including the atomic acknowledgement cleanup protocol.
  Final 26.2 pass 4 at 16:02:48 UTC reran the frozen production and test baseline,
  including mutable-profile/reallowed-login regressions and descriptor-identity
  verification. The build agent archived matching source hashes in
  `folia-tools/runtime-evidence/pass4-final26.2-source-sha256.txt` in the
  acceptance workspace.
- Real clients tested offline GUI half-stack deposit, login denial while the
  offline view held its lease, close and reconnect. The final saved player NBT
  contained 32 diamonds on the target and 32 on the other viewer (64 total in
  saved main inventories); this count excludes cursor/ground/journal escrow and
  is not a blanket assertion about untested item locations. Test helper `OfflineCodecRuntimeChecks` is test-only and must
  not be packaged in the production artifact.

The same offline production binary also passed initialized-server codec/storage
checks on official Folia 26.3 build 6 at 16:10:12 UTC. This establishes these
particular offline paths on that build, not blanket compatibility. A separate
equipment test helper initially encountered a changed CraftItemStack signature;
after the test-only compatibility adjustment it passed 14 native assertions at
16:15:16 UTC, alongside another passing offline codec/storage run. The complete
adapter compatibility gate must still be evaluated separately.

These are not a claim that every real network race or power-loss fault has been
exhaustively tested. See the current integration report and release gate.
