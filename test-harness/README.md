# Adversarial validation (experimental, isolated only)

## Recorded execution

Current login-priority acceptance: [RC2_LOGIN_RESULTS.md](RC2_LOGIN_RESULTS.md). Old login-denial cases are historical only; `expectblocked` refuses to run for RC2.

Sanitized aggregate results and final artifact binding: **[EVIDENCE_SUMMARY.md](EVIDENCE_SUMMARY.md)**. Raw runtime evidence is local-only, excluded from source/distribution archives. All fixtures are disposable synthetic test-player data, not production data.

Latest fresh-workspace paired26.2/26.3 administrative and GUI evidence: **[PARITY_RESULTS.md](PARITY_RESULTS.md)**. Historical baseline results below must not be treated as proof for a different artifact.

- `./test-harness/run-synthetic.sh`: **23/23 passed** on JDK25. Includes existing transfer fault tests plus 1,000 simultaneous-editor races using three distinct synthetic owner executors, a retired-destination/no-replay check, and 1,000 offline-versus-login admission races. The first run took 222.753ms for 3,000 synthetic transfers; use the latest log for repeat-run timing. **Not Folia performance or real-region evidence.** Log: sibling `folia-tools/adversarial-owner-tests.log`.
- `./test-harness/build-live-probe.sh`: **compiled** against official Folia26.2 build7-beta runtime libraries. Produces `test-harness/InvSeeLiveOwnerProbe.jar`; does not install or run it.
- Real-player execution: **RUN through official ViaVersion+ViaBackwards translation; see LIVE_RESULTS.md for per-artifact outcomes and remaining gaps**. Native26.2 automated clients remain unavailable. Official npm `minecraft-protocol@1.68.0` supports versions through26.1; `minecraft-data@3.119.0` has no26.2 schema files or pc dataPaths entry. Release tarballs were retrieved with `npm pack --ignore-scripts` and SHA512 checked against registry. No community fork installed, no server checks bypassed. Official Mineflayer dependencies were subsequently installed with scripts disabled for translated26.1 clients.

- `./test-harness/run-player-tasks.sh`: actual production PlayerTasks with deterministic API proxies passes retirement, rejection, session replacement, cancellation and running-task shutdown-drain checks. Not live scheduler evidence.

## Live owner probe prerequisites and scope

Only on a disposable isolated localhost Folia26.2 server. Never put this test plugin on production: it seeds a stack of64 diamonds into a test player's empty slot0, moves it, then removes the exact seeded stack on success. A failed/aborted run intentionally does not guess at cleanup. Inspect all three test inventories before retrying. The test journal is memory-only: crash/restart durability is **not** validated.

1. Compile with the script; configure `FOLIA_SERVER`, `FOLIA_TOOLS`, or `JAVA_HOME` if paths differ. Copy the resulting jar into the disposable server's plugins directory and restart (not plugin reload).
2. Connect three real disposable26.2 clients. Use console commands to place them thousands of blocks apart in loaded areas; keep them still and alive. Slot0 of every player must be empty. Do not run other inventory-changing plugins or touch those slots during the baseline.
3. From server console: `ownerprobe playerA playerB playerC`.
4. The probe checks live session identity and `Bukkit.isOwnedByCurrentRegion(player)` in every endpoint operation. Before mutation, each player's owner task verifies neither other player is currently owned by that region. It refuses shared-region players rather than inferring separation from coordinates/thread names.
5. It runs100 pairs of competing transfers plus100 returns through the production `TransferService`; every round requires exactly one completion and one conflict and exactly one physical stack. It reports p50/p95 round-trip timings and successful cleanup. Save the server log and player identities/locations with evidence.

This probe uses its own correctly owner-dispatched Bukkit endpoints. It **does not validate production `InventoryAccess`, the InvSee GUI/event cancellation, persistence, offline NBT integration, or real disconnect/login races**. A PASS means only the stated live-owner core scenario.

## Broader release-gate matrix (not exhaustively executed)

Some individual cases below have passed as recorded in `LIVE_RESULTS.md` and `PARITY_RESULTS.md`; the complete phase-by-phase matrix remains open. Run remaining cases through the actual InvSee GUI with the three clients, recording pre/post item counts in all target/viewer inventories, cursors, ground entities and durable journals; distinguish physically present items from recovery records (never count the same escrow as both).

- Two viewers simultaneously remove/split the same target slot; hotbar swaps, shift-click, drag, double-click and creative actions must not introduce a second mutation path.
- Teleport the target and each viewer across distant regions while operations are in flight. Verify owner assertions continue to pass and no access occurs under the previous owner.
- Disconnect source/destination/viewer before reserve, after reserve, during delivery, and after delivery-before-ack; reconnect, reopen and verify stale sessions are rejected. An uncertain result is not proof of loss-free automatic recovery.
- Offline edits versus concurrent login: admission must serialize the operation; inspect playerdata after save/login and verify exact item count/metadata. Synthetic coordinator tests alone do not establish event wiring or NBT correctness.
- Fail journal write and offline save, retire the scheduler, disable the plugin, and terminate the isolated server at every write-ahead phase. Restart without replaying uncertain operations; reconcile both inventories manually before any recovery.
- Load/performance: measure actual Folia region tick times, async I/O queue depth, pending operations, and p50/p95/p99 operation latency under multiple independent viewers. The synthetic timings and the probe's low-volume in-memory journal are not representative production throughput.

No full26.2+ safety, crash atomicity, GUI correctness or production readiness is claimed by this harness.
