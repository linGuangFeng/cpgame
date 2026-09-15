# Jungle Party raw gid 33 protocol

The local service retains the confirmed form-encoded endpoints `config`, `spin`, `log-list` and
`log-view` under `/cp/api/v1/jungle-party/`. A paid request starts exactly one Round. Free-spin
continuations have `ba=0`, `gt=2`, increasing `nfsc`, and remain in the same Round until
`ss=1 && nfsc==fsn`. History is finalized only then.

The board is 5×3 reel-major with 25 evidence-derived paylines. A line award is the config paytable
value multiplied by `bl*bs`, by `max(1,rpx)`, and by two if the matched prefix contains Wild.
This formula independently reproduced all 2,099 captured Steps with zero mismatch.

Redis is a local replica contract: one independently verified complete Round per list member.
Original Redis keys and codec are unknown and are not claimed.

## Controller contract v3

The Java controller requires a runtime-selected port in the managed range 50000-59999, binds to
`0.0.0.0`, and serves both the static observed client and API from the same process. Sessions are
isolated. Spin idempotency keys replay the exact prior response without charging balance or
advancing a Round. Session balance, completed history, idempotency records, and an unfinished free
Round are durably written with the rules hash and atomically restored after process restart.

The config response projects an unfinished Round through `last`; the next spin resumes at the next
`nfsc` and history is still committed only at `ss=1 && nfsc==fsn`. Direct mode generates every
Round from `GameRuleCore` at request time. Redis mode claims one complete independently verified
Round per list member. The observed client language set is `bn,en,es,fr,id,ko,pt,th,tr,vi`.

## v37 complete-Round generation model

The runtime model is a hierarchical empirical joint kernel trained on 1,375 real provider Rounds;
100 predeclared real provider Rounds are excluded from all training pools. It first samples the
joint Round macrostate (`ROUND_OUTCOME_CLASS`, `ROUND_STEP_COUNT`, free-spin totals, multiplier and
retrigger count), then samples a complete 15-element state kernel for `INITIAL_PAID_SPIN` and each
`FREE_SPIN_CONTINUATION`. It does not sample cells from independent marginals and does not construct
losses using a restricted symbol alphabet. Paytable-equivalent global symbol permutations and the
payline-preserving vertical symmetry provide new states while retaining the learned joint structure.

The initial entry is `COMPLETE_INITIAL_STATE`; free-spin continuation is a complete subsequent deal.
There is no cascade or respin entry in this game. Were one present, only adjacent-state differences
would be represented as `NEWLY_DEALT_POSITIONS`. Independent validation compares the 100-Round
`REAL_PROVIDER_HOLDOUT` with 10,000 newly generated complete Rounds, including per-entry
`STATE_ELEMENT_COUNT_VECTOR` and whole-Round joint-distribution tests.
