# 2350-Curupira Java Server API

This Maven project implements the accepted `rulesHash`
`d5421efc347392576699048c0538c72d669056017eb723ae2a046fa7bac71404`.

The playable API depends on the normal Maven artifact delivered by
`generator/2350-Curupira/dist/maven-repository`; it keeps no duplicate rule-core source.
The service does not generate boards. Its runtime chain is `ApiController -> SessionState ->
DemoSelectionPolicy -> RedisRoundStore -> MinimalFactCodec -> SpinProjector`. Init and every
paid result, including multiplier zero, must decode a complete formal Redis member produced by
the generator project. Empty/invalid cache data is an explicit error; captured responses,
fixtures and Java live generation are never runtime fallbacks.

Implemented behavior IDs:

- B001/B002: the accepted static publish entry and language fallback contract.
- B003/B004: fixed paylines, payout table, Wild substitution and Scatter count.
- B005: after a cached trigger, `type=2,game_type=2` consumes the complete six-Step Mary type0 fact.
- B006: after a cached trigger, `type=2,game_type=3` consumes the complete terminal Hold Mary type1 fact.
- B008/B015: initial configuration and user/balance session endpoints.
- B009: Init peeks a complete cached zero-multiplier fact and later restores the last projection without creating or charging a Round.
- B010: paid `type=1,game_type=1` claims a cached ordinary terminal Round. Six configured actual-multiplier bands select a target, then the store searches downward in the same ordinary family.
- B011: one History row per paid Round with exact decimal `order_id` prefix.
- B012: game identity is `2350-Curupira`.
- B013: three Wilds in a column derive the main-game expanding column.
- B014: form codec and evidence-backed MD5 `signapt` verification.

B005 and B006 retain an UNKNOWN original-wire evidence status, but the local delivery enables
explicitly labeled rule-based projections backed only by complete Redis Mary facts. B007 Feature
Buy remains unsupported and returns an explicit error without charging balance or creating History.

Build and test:

```powershell
mvn.cmd test
mvn.cmd package
```

Run with `run-api.cmd`. The default bind is `0.0.0.0:19500`; override the port with
`CURUPIRA_API_PORT`. Redis connection timeout defaults to 30 seconds. Normal, free and Hold
each have six explicit Demo band weights under
`curupira.demo.{normal|free|hold}.band.*.weight`. A selected target searches downward only within
its own Redis family. Build the generator dist before compiling this project; the server delivery is
`dist/controller.jar`.

`run-local.cmd` also starts the JDK static server on `0.0.0.0:8500` and serves only
`publish/2350-Curupira`. The publish fallback derives the API host from the visitor's
current hostname, so LAN clients do not receive a loopback API address.
