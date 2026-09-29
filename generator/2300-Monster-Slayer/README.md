# Monster Slayer 2300 — rule-driven purchase generation

Purchase types 3/4/5 now synthesize complete hunting rounds using SecureRandom and the shared rule engine. No captured complete round is a runtime result source. Captured purchase resources live under src/test/resources only and must never be packaged in the Loader.

MonsterFeatureGenerator advances movement, weapon attacks, partial rocket hits, collectors, heart damage/upgrades, traps, split symbols and Hunter's Fury. HuntRules independently replays stored weapon decisions without RNG or trusted payout fields. ResultUtil includes the ICE global multiplier, the upgraded ICE Wild multiplier, and split-cell way counts. The independent payout regression covers the original ordinary/special wire amounts.

New purchase results use MS4B with raw binary HTB1 attack traces. Controller reconstructs animation JSON at the current bet from these facts; no historical animation or payout JSON is stored in new members. Ordinary results remain MS3. All rounds terminate naturally; an attempt exceeding the observed 32-delivery bound is rejected, never truncated. Each generation call permits at most 200 such bounded attempts. The observed 9-Wild, 3-entry tree path and 11-hit shadow collector bounds are enforced.

The immutable aggregate feature-reel model is loaded once. It samples weighted whole paying reel triples, then applies actual battle overlays. Attack rows are conditionally sampled from current collision occupancy, with weights fitted to training attacks. This is an estimated generation model, not a claim of the original RTP. The 100 reserved natural complete rounds and final 5 purchases per mode were excluded from model fitting. Sparse opening-reel and reward evidence limitations are recorded in the repair report.

Natural generation.special-count remains disabled: the purchase repair does not claim completed natural Scatter 1-to-2 progression. generateSpecial is an internal test path for the evidenced initial states; it is not a newly enabled production pool.

Redis values are hundredths of base bet: 10000=100x, 30000=300x. Buy costs 60/500/200 only affect the charge. Limits filter valid complete rounds, not individual deliveries. The Loader still streams validated batches and writes ZADD + RPUSH + LTRIM. Starting a requested pool replaces that pool after its first valid batch; zero-count pools are untouched. Existing configuration values and connection credentials are preserved.

## Required rollout order

1. Replace/restart the 2300 Controller and every production consumer using the old ResultUtil. Version: 2300-monster-slayer-v8-rule-generated-hunt.
2. Rebuild the three purchase caches using the matching new Loader. Old special members had incorrect multipliers and sometimes incorrectly restored monster IDs; appending new results to them is not a valid migration.
3. Verify gameResult continuations, totals and History with the updated service before accepting the deployment.

Double-click dist/start-generator.cmd (or use --no-pause). Configuration is read once per run from dist/generator.properties. Already-running Java processes will not load changed files automatically.

Report: reports/2300-Monster-Slayer/rule-generated-hunt-20260916/README.md.
