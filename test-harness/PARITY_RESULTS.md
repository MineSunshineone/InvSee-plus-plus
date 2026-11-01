# Fresh administrative parity results — 2026-11-01

All fixtures were generated on disposable isolated test servers; no production player data was used. Raw logs, identifiers and saved inventory dumps remain local and are excluded from publication. See [aggregate evidence](EVIDENCE_SUMMARY.md).

## Folia26.3 first reconstructed candidate

- Server: official26.3 build6-alpha; Java25; three real localhost clients translated from26.1 by official ViaVersion/ViaBackwards5.12.2. Telemetry disabled before launch. Clients stationary; no movement compatibility claim.
- Same26.2-compiled canonical jar SHA256: `9ba09be80ad21225d43b58b7f969938770cc3d284ba0902851d232eec2c4f2b0`.
- Acceptance jar (injected test checks) SHA256: `c50e159cc80f6038f8cdbddafb7351d602c3742839e47042561e80266f56980c`.
- This candidate precedes the narrow console-permission owner-dispatch fix. Latest-candidate confirmation is separate below.

Passed runtime cases:

1. Native planner10, equipment14 and initialized offline codec/storage checks. Real-player core probe100 races with actual distinct-region ownership assertions; p50~200ms/p95~200.8ms (custom endpoints/in-memory journal, not production throughput).
2. Actual GUI simultaneous64-diamond withdrawal: exactly one completion/onebusy refusal. Ordinary shift return restores target64/viewers0. Boots→wrong head rejected; boots→feet completed and saved.
3. Metadata seed adds3 named/lore/PDC diamonds in empty slot9. Online clone target→A preserves exact slot contents, item components and boots. Give100/clear20 produce A167 then147, source67 unchanged.
4. Capacity overflow, unsupported component syntax and trailing arguments refused; saved relevant inventories unchanged.
5. Test-only bounded journal delay observes command journal access queued, revokes command permission on actor owner, then releases:CONFLICT/no mutation. Offline required-bypass revocation repeats the same result. This is an observed journal-access boundary, not a claim about a particular fsync phase.
6. Offline cached-name main give5 emeralds, filtered clear2 diamonds and clone→onlineB complete. Saved clone retains name/lore/PDC and equipment exactly (top-level inventory lists normalized by slot).
7. Offline ender give70, clear6 and clone→B preserve exact64 ender items. Full ender clearB preserves main/equipment; full main clear offline target removes equipment while preserving ender64. Target reconnects with main0 as expected.
8. Online cloneB→target restores65 diamonds+metadata+boots. Full clearA removes main/equipment; full ender clear target leaves main65. Two-actor simultaneous give1: one completion/onebusy refusal, exact+1.
9. Final saved target66/A0/B65, enders empty:131 diamonds. These include intentional give/clone creation and clear destruction, not a transfer-only conservation total.
10. All17 patch records loaded and checksummed by production FilePatchJournal:15COMPLETED,2CONFLICT,0unresolved.
11. Disable production plugin, then replay8 stale normal/shift/hotbar/drag/drop/double-click packets: inspector closes, cursors empty, saved/client131 unchanged.

Sanitized public evidence: [EVIDENCE_SUMMARY.md](EVIDENCE_SUMMARY.md). Initial read-only snapshot used an obsolete world path and was corrected to `world/players/data`; no missing save was counted as zero. Vanilla `item replace`/`save-all` were unavailable on this server; equipment was set through actual GUI and durability checked through successful transaction saves and player disconnect, not those unsupported commands. Mojang service-discovery DNS warnings are unrelated to inventory ownership; no plugin thread-ownership failures observed.

## Latest console-fix candidate — paired 26.2/26.3 confirmation passed

Canonical SHA256: `479aa40ac35a18425463965adba66b15368113df449f4dbe61a02a4d0dc72b44`. Acceptance26.2 SHA256: `f9c8893c509e68512934619b582b0729e6cf05c297d845a7b81eb6be0f1b529e`.

The full fresh-world sequence above was repeated successfully on26.2 with this candidate, including the native checks,100 distinct-owner races, actual GUI transfer/equipment cases, all6 administration commands online/offline, metadata/equipment clone equality, no-mutation failures, both late permission fences, full-clear isolation, command race and disable/stale-packet safety. Additionally, real console give1 and clear1 completed with `console.has-all-permissions: false`, exercising the corrected global-owner dispatch.

Durable audit:19 checksummed patch records,17COMPLETED/2CONFLICT/0unresolved. Final intentional item ledger: target66/A0/B65, enders empty; disable/replay left131 unchanged. Sanitized public evidence: [EVIDENCE_SUMMARY.md](EVIDENCE_SUMMARY.md).

Harness correction: console give1/clear1 kept total64 but split it into63+1, while the race helper initially assumed a single64 stack. The resulting63 transfer completed correctly (1+63 conserved), then was explicitly returned and merged; the proper full-stack race passed. Future harness runs validate stack shape before sending any clicks. The initial timeout is not counted as a full-stack pass or hidden as a plugin error.

### Same-binary 26.3 confirmation

The latest canonical binary also passed native planner10/equipment14/codec+storage checks, console give1/clear1 with `console.has-all-permissions: false`, online clone/give/clear, offline main and ender give/clear/clone, and exact saved metadata/equipment clone comparisons. Both command-permission and required-bypass revocation at the observed journal-access boundary returned CONFLICT with saved inventories unchanged. Full clears preserved the other inventory kind. Actual GUI withdrawal and return of the3 named/lore/PDC diamonds preserved their saved components and conserved132 diamonds. Disable followed by8 stale packets left target66/A0/B66, empty enders and empty cursors unchanged.

This confirmation reused the first26.3 world's131-item fixture; cloneB65→66 intentionally added1, yielding final132. The33 accumulated checksummed patch records comprise29COMPLETED/4CONFLICT/0unresolved, including the earlier17 records; the latest run added16 records (14COMPLETED/2CONFLICT). Latest26.3 logs contain no scenario-error. Sanitized public evidence: [EVIDENCE_SUMMARY.md](EVIDENCE_SUMMARY.md). The initial full26.3 distinct-owner/GUI-race/equipment suite belongs to the preceding binary and is not relabeled as latest-binary execution; the final26.2 full suite covers those cases on the latest binary.

Full crash/power-loss fault injection, sustained-load performance, all third-party-plugin races and native26.3 clients remain unproven.

### Final RC1 packaging verification

Final distributable SHA256: `4fbb0f66dc4fb1e10813f16de016acb5d09d5b482c8c7a220c59819cba15e70e`. All63 production `.class` entries are byte-identical to the tested `479aa40a…` candidate; descriptor/build metadata changed for RC1 packaging. The exact final jar loaded/enabled on both26.2 and26.3 and completed console online `invgive`, `invclear`, `endergive`, `enderclear` with console blanket permissions disabled. Both servers stopped gracefully (launcher exit0). Earlier offline/pre-readiness smoke requests were correctly refused for missing exemption bypass without mutation; they are not counted as successful command execution. Full suites were not repeated after metadata-only packaging. Sanitized public evidence: [EVIDENCE_SUMMARY.md](EVIDENCE_SUMMARY.md).
