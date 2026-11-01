# RC2 login-priority validation — 2026-11-01

All fixtures are disposable localhost test data; no production player data was used. Raw logs, identifiers and inventory dumps stay local and are excluded from publication. RC1 login-refusal tests are historical and **not** RC2 acceptance criteria.

## Artifact and scope

Candidate SHA256: `50d90c3f3119573da783cfe27e6da8e64d79d9ca2b6986f8186309615f509cc5` (compiled against26.2). Paired26.2/26.3 actual-client cases passed. Official Folia26.2 build7-beta /26.3 build6-alpha, Java25, official ViaVersion/ViaBackwards translation from automated26.1 clients; telemetry disabled. No native26.3-client compatibility claim.

Final RC2 distributable SHA256: `e769c90cd1f6d5f6685bda05110407fc0638ff1d13710580fb04be37f5a3b450`. All65 production class entries are byte-identical to the tested candidate; only packaging metadata changed. The exact final jar, without injected test classes, separately passed first-attempt offline-main-view login handoff and online-view reopening on both26.2/26.3, with unchanged fixture totals and no harness errors. Both final-smoke servers stopped cleanly with launcher exit0 and telemetry disabled. These final smokes do not claim the entire candidate suite was reexecuted after repackaging.

## Actual-client cases

| Case |26.2|26.3|
|---|---|---|
| Idle offline main inspector: first connection succeeds, inspector closes, counts unchanged |PASS|PASS|
| Idle offline ender inspector: first connection succeeds, inspector closes |PASS|PASS|
| Selected source without transfer: first connection succeeds;8 stale packets cannot mutate |PASS|PASS|
| Pinned administration transaction queued behind4s journal hold: completion precedes join; loaded items match committed state |PASS|PASS|
| Pinned GUI transfer queued behind4s journal hold: stale session conflicts without mutation, then first connection succeeds |PASS|PASS|
| Reopen online inspector after handoff: metadata withdrawal/return succeeds and conserves items |PASS|PASS|
| Actual owner mutation during delayed online administrative CAS: conflict rather than overwrite |PASS|PASS|

Each `loginonce` case calls connect exactly once; no automatic reconnect/manual retry.26.2 idle/selected handoffs completed in roughly0.9s including a500ms post-spawn stability check. Artificial4s journal holds produced roughly4.9s total connect/check time. **These are controlled-delay correctness observations, not natural latency or load benchmarks.** The barrier is at journal queue admission, not a disk fsync boundary.

The26.2 run ended with the initial131-item aggregate fixture restored after explicitly accounting for intentional give/clear mutations; no harness scenario errors. Native initialized storage checks additionally cover idle handoff and pinned rollback/ack after login intent. The newest explicit queued-CAS I/O-worker-latch check passed on26.3 and in a separate additional26.2 initialized runtime pass. Both classpaths passed108 unit tests.

The26.3 run restored its initial132-item aggregate fixture. A cleanup clear was sent before the deliberately delayed preceding command terminated; it was correctly refused as busy, and a premature expected-count assertion timed out. After observing the preceding CONFLICT and unchanged68-item target state, one explicit clear2 completed and restored66. These two harness errors are disclosed, not counted as plugin failures or successful cleanup; the corrected cleanup was verified in client and saved data.

Both test servers shut down cleanly. The26.3 launcher returned exit1 from the disclosed harness assertions; this is not presented as a zero-error run. The additional26.2 native refresh exited0.

## Limits

The full duplicate-connection/timeout/disable matrix, exhaustive crash phases and sustained-load performance remain unproven. A pending GUI operation may safely conflict when its offline session closes; login-priority does not promise every racing administrator edit succeeds. Normal tested login paths succeed on the first connection after admitted work reaches terminal state.
