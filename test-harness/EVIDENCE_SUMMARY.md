# Sanitized validation summary — 2026-11-01

## Current RC2 login-priority validation

See [RC2_LOGIN_RESULTS.md](RC2_LOGIN_RESULTS.md) for paired first-attempt login, offline-view closure, transaction draining and post-handoff online editing results. RC1 login rejection is historical, not current acceptance behavior. Final RC2 SHA256: `e769c90cd1f6d5f6685bda05110407fc0638ff1d13710580fb04be37f5a3b450`;65 production classes match the paired tested candidate. The exact final jar passed first-attempt login/view handoff smokes on both versions.

## Historical RC1 parity baseline

All players, inventories and worlds were generated for disposable isolated localhost testing. **No production player data was used.** Public evidence contains aggregate outcomes and artifact hashes only. Raw logs, player identifiers and saved inventory dumps are retained locally and excluded from publication and distribution archives.

## Environment and artifact binding

- Official Folia26.2 build7-beta and26.3 build6-alpha, Java25.
- Three real server players in distinct ownership regions; automated26.1 clients through official ViaVersion/ViaBackwards5.12.2.26.3 clients stationary. Telemetry disabled before launch.
- Tested candidate SHA256: `479aa40ac35a18425463965adba66b15368113df449f4dbe61a02a4d0dc72b44`.
- Acceptance-only jar SHA256: `f9c8893c509e68512934619b582b0729e6cf05c297d845a7b81eb6be0f1b529e` (includes test check classes; not the distributable).
- Final RC1 distributable SHA256: `4fbb0f66dc4fb1e10813f16de016acb5d09d5b482c8c7a220c59819cba15e70e`.
- All63 production class entries are byte-identical between tested candidate and final RC1. Packaging metadata changed. Exact final RC1 loaded/enabled on both versions and completed four online console administration smokes per version; both servers stopped gracefully, launcher exit0.

## Results

- 101 unit tests passed against each version's runtime classpath.
- Initialized native planner10 and equipment14 assertions passed, alongside offline codec/storage and whole-inventory patch checks.
- Latest26.2 full suite:100 competing transfers using actual distinct player owners; actual GUI concurrent editing and equipment checks; all six main/ender give, clear and clone commands online/offline; metadata/equipment clone equality; capacity and invalid-argument refusals; late permission and bypass revocation; inventory-kind isolation; simultaneous administration; disable plus8 stale packets.
- Latest26.2 patch audit:19 records,17COMPLETED/2CONFLICT/0unresolved. Final intentional fixture total131 unchanged by stale replay.
- Latest26.3 confirmation: native checks, all six administration commands online/offline, exact metadata/equipment cloning, both late permission fences, cross-kind clear isolation, actual metadata GUI roundtrip, disable plus8 stale packets. Final intentional fixture total132 unchanged by stale replay. Accumulated33 patch records:29COMPLETED/4CONFLICT/0unresolved;16 records were added by this confirmation.
- A preceding26.3 candidate also passed the full owner/GUI/equipment suite. That full execution is not attributed to the latest binary. Candidate-specific details are in [PARITY_RESULTS.md](PARITY_RESULTS.md).

## Interpretation and remaining limits

Give/clone intentionally create items and clear intentionally removes them; reported fixture totals are explicit administrative ledgers, not transfer-only conservation totals. The low-volume owner probe uses custom endpoints and an in-memory journal: its roughly200ms round-trip measurements are not production throughput.

One26.2 harness timeout arose from assuming one64-item stack after a console operation had split it into63+1. The63-item transfer conserved items, was explicitly returned/merged, and the corrected full-stack race passed. The harness now checks stack shape. Missing-bypass smoke attempts were correctly refused and are not counted as successful operations.

Full crash/power-loss phase injection, sustained-load performance, every third-party-plugin race and native26.3 client behavior remain unproven. Passing these bounded tests is not a claim of universal crash atomicity or unconditional production readiness.
