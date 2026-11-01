# RC2 login-priority acceptance plan

Status: prepared, not executed. RC1 login-refusal evidence does not establish RC2 behavior. Run only after the RC2 source freeze and explicit runtime/FIFO handoff on telemetry-disabled disposable localhost servers. No production data. Publish only sanitized aggregate results.

## Required cases

1. **Idle offline view:** record saved counts/components; disconnect target; open offline inspector without editing; run `loginonce T <expected diamonds>`. Exactly one connection must spawn without a kick/retry. `assertclosed A` confirms the old offline inspector closes. Saved/client counts unchanged. Reopen an online inspector and perform an ordinary roundtrip.
2. **Accepted write drain:** use a bounded test-only barrier at an actual accepted offline storage operation, not merely a journal-access queue. Start one edit, observe operation acceptance/barrier, start one login. It must remain pending until durable storage completes, then spawn with the committed item state. Verify component equality and terminal journal state. If such a barrier is unavailable, label this exact phase untested rather than infer it from elapsed time.
3. **Pending CAS versus login:** hold the documented pre-CAS/admission boundary, request one login, release the barrier. Either an already-admitted write finishes durably before spawn or the edit conflicts before mutation; no partial saved inventory. Require no manual login retry and unchanged unrelated inventory kind.
4. **Stale inspector:** after handoff replay captured old-window ordinary/shift/hotbar/drag packets. Old offline state must not mutate the online player. Compare client and durable counts; reopen online view to demonstrate normal editing still works.
5. **Concurrent online mutation:** after handoff, change a target slot on its actual owner while an administration operation has a stale expected snapshot. Require conflict/refresh, not stale overwrite; keep intentional fixture mutations separate from conservation totals.

## Harness changes

`loginonce T <count>` makes exactly one connection attempt, waits for spawn and expected inventory, verifies session remains connected, and records latency. It never retries. `assertclosed A` waits for the stale view to close. The former `expectblocked` command now fails without connecting: login rejection is not an RC2 success criterion.

For all cases record artifact hashes, barrier location, command outcome, persisted before/after equality and terminal journal audit locally. Do not treat a journal executor delay as proof of an accepted disk write. Native clients, exhaustive crash-phase testing and sustained-load performance remain separate limits.
