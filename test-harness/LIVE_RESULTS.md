# Isolated live evidence — 2026-10-31 (preserved baseline; parity stage follows)

Environment: official Folia26.2 build7-beta, Java25,2 region threads, localhost25572, disposable offline-auth whitelisted test identities. Official ViaVersion+ViaBackwards5.12.2 (release asset SHA256 verified by build owner) translate official Mineflayer4.39.0/minecraft-protocol1.68.0 clients using26.1. These are real connected ServerPlayers, not injected entities. Native26.2 client compatibility is not established by translated-client tests.

Evidence outside repo: sibling `folia-tools/runtime-evidence/` archives server/bot logs, acceptance artifact hashes, source hashes and pass1 jar. Active logs: `server-combined.log`, `bots-runtime.log`. Do not conflate passes: implementation changed between them.

## Pass1 (15:52–15:54 UTC)

- Live owner probe:100 races,3 distinct current ownership regions asserted via Bukkit from each owner's scheduler; exactly one receiver per round and cleanup passed. p50~199.989ms/p95~201.821ms. This uses production transfer core with custom endpoints and in-memory journal, not full plugin performance.
- Actual GUI simultaneous full-stack withdrawal: one COMPLETED; competing viewer busy-rejected. Final target0/viewerA64/viewerB0; all cursors empty.
- Actual GUI reverse right-click half: target32/viewerA32; saved NBT independently parsed and matched.
- Shift/hotbar actions rejected in this build (later build intentionally supports them; not a current expectation).
- Permission revoked by deop between selection and destination click: read-only response, no item mutation.
- Target teleported from x0 to8192 after selection; subsequent transaction completed on moved player.
- End-of-pass persisted main inventories total64; three transaction records loaded/checksummed by actual FileJournal all COMPLETED. `durable-snapshot-first.jsonl`, `durable-journal-first.log`.
- Initialized-runtime offline codec24 checks and storage coordinator/CAS/ack/fence checks passed.

## Pass2 (15:55–15:56 UTC)

- New shift transfer32 to target; number-key transfer merges it with viewer's existing32 to64; both COMPLETED.
- Offline GUI half deposit to disconnected target: saved target32/viewer32/viewerB0, total64. Transaction COMPLETED.
- Real reconnect while offline GUI lease held rejected with inventory-being-edited message.
- Expanded runtime codec/storage checks including bounded queue and drain passed.
- After lease release, target reconnect succeeded and client inventory was32; viewer32/other0, total64.

## Pass3 (15:58–16:00 UTC)

- Inode-durability correction included; full concurrent GUI race and offline half deposit rerun successfully.
- Offline reconnect refused while lease held, then accepted after explicit window close; saved and client counts32/32/0.
- Target disconnect after source selection, before reservation: actual production transaction CONFLICT; refresh refused disconnected session and closed GUI. No item mutation, persisted32/32/0; reconnect succeeded.
- No Folia ownership/thread-check errors observed. Public-key DNS error is unrelated to test clients' isolated offline-auth admission.

## Pass4 / Pass5 on 26.2 (16:02–16:09 UTC)

- Native equipment checker: 14 initialized-component assertions passed, including custom BODY/SADDLE entries and allowed entity restrictions.
- Actual GUI rejected boots in a wrong equipment slot; feet slot36 accepted and saved in playerdata equipment.
- Live online snapshot reflected an emerald given externally to the target.
- Concurrent full-stack race, half transfers, offline main/ender deposits and withdrawals, blocked login while leased, and successful reconnect after release passed.
- A completed transaction recovery claim was refused, without minting another item.
- Global-scheduler plugin disable closed open menus. Eight stale ordinary/shift/hotbar/drag/drop/double-click packets did not change saved inventory counts; cursors cleared. Final saved total64.
- Pass5 source preceded the final bypass-revocation fix; the later paired check below covers that change.

## Preserved paired baseline, including authorization fix363018eb5

Exact runtime dependencies:
- Folia26.2 build7 BETA, commit14b7fee5c866fca9a40ede4a58998fb928140f65.
- Folia26.3 build6 ALPHA, commit866492320452cf4134b6f2d4036235852eb275df.
- The whole module and65 JUnit tests compiled/passed separately against each runtime's actual API/NMS/library classpath.
- The SAME26.2-compiled production binary was used for both live environments; only the acceptance copy's descriptor was enabled and test-only runtime checker classes injected.

Canonical experimental distribution SHA-256:
007ee80a855df374ba62999614059da20968a715661849c0d5c63f63e4859b46.
Acceptance-only jar SHA-256:
c8c682e599b01334ce6fd9e6055c7ef03b61209bc59c2933311936d75deab6d0.
Preserved evidence directory outside git: ../folia-tools/baseline-20261031T1616Z/.
It includes source/input fingerprints, both compilation/test outputs, runtime logs,
and independent saved-NBT snapshots. Servers were stopped cleanly after tests.

### 26.3 observations

- Initialized codec/storage checks passed with the26.2-compiled production binary.
- The equipment TEST helper initially hit a removed CraftItemStack overload; casting to the shared ItemInstance overload fixed test-only linkage. No production equipment method changed for that issue.
-100 real-player races on three distinct ownership regions passed (owner-thread assertions on each endpoint), p50~199.988ms/p95~200.871ms. This is a transfer-core/in-memory-journal probe, not full-plugin or sustained-load performance.
- Actual GUI competing full-stack withdrawals produced one completed transfer and one busy rejection, total64.
- Actual offline half deposit, blocked reconnect while leased, bypass revocation after selection, window closure, lease release, and subsequent reconnect passed. Persisted target32/viewerB32/viewerA0.
- Plugin disable followed by eight stale menu packets left no menu/cursor item and saved total64.

### Latest 26.2 authorization regression

On the identical latest binary, native equipment and initialized codec/storage
checks passed again. With an offline target selected, revoking the viewer's
bypass grant before a destination click closed the menu and left independently
parsed saved inventories equivalent at the structured snapshot level.
Login succeeded after the lease drained. Final saved total64.

### Harness limits and observed sequencing errors

Official Mineflayer/minecraft-protocol packages support26.1, not native26.2/26.3.
Official ViaVersion/ViaBackwards5.12.2 supplied translation. On26.3, translated
movement packets were rejected by the server. Optional MC_STATIC_CLIENTS=true
suppresses only outbound movement packets; teleport confirmations, inventory
packets, and real connections remain. No server movement or region/thread
safety checks were disabled. Server-side teleports separated the real players.
Thus26.3 results establish stationary inventory scenarios, NOT native-client
movement/protocol compatibility.

Immediate close-and-reconnect attempts sometimes hit the still-draining admission
fence and were correctly rejected. After observing closed windows and allowing
the asynchronous drain, reconnect succeeded. Such attempts set the interactive
bot process exit code nonzero; selected results above rely on specific
server/client evidence and durable snapshots, not that process exit status.
One attempted click after an already-closed window produced a client-side
invalid-operation assertion, with no server inventory mutation.

## Remaining limits

This is an experimental baseline, not full-function/production acceptance.
The separately authorized give/clear/clone parity stage is not covered.
Native26.2/26.3 client behavior, exhaustive crash/failure injection at every
persistence phase, sustained load/queue latency, every disconnect or teleport
ordering, and arbitrary other-plugin interference remain unproven.
Later versions such as26.4 are not tested. Successful selected scenarios do not
prove no loss under arbitrary server/power crashes. Source support gating remains
a separate release decision.
