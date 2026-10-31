# Isolated live evidence — 2026-10-31 (in progress)

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

## Limits still requiring coverage

Latest inode-durability fix requires subsequent pass. Native26.2 client behavior, exhaustive crash/failure injection across every persistence phase, sustained load/queue latency, all disconnect/teleport orderings, other-plugin interference and26.3+ are NOT established. No production-readiness claim. Successful counts in selected scenarios do not prove no-loss under arbitrary server/power crashes.
