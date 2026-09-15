# 2350-Curupira Java Server API

This Maven project implements the accepted `rulesHash`
`d5421efc347392576699048c0538c72d669056017eb723ae2a046fa7bac71404`.

The playable API depends on the normal Maven artifact delivered by
`generator/2350-Curupira/dist/maven-repository`; it keeps no duplicate rule-core source.
The shared Java chain is `ApiController -> SessionState -> GameRuleCore ->
CandidateBoardGenerator -> RoundFactory -> ResultUtil -> RoundVerifier`. Every paid board is generated with `SecureRandom`,
then independently evaluated against the current game's 5x3 column-major board, 25
fixed paylines, paytable, Wild and Scatter rules. No captured response is a runtime source.

Implemented behavior IDs:

- B001/B002: the accepted static publish entry and language fallback contract.
- B003/B004: fixed paylines, payout table, Wild substitution and Scatter count.
- B008/B015: initial configuration and user/balance session endpoints.
- B009: Init restores the last projection without creating or charging a Round.
- B010: paid `type=1,game_type=1` terminal ordinary Round.
- B011: one History row per paid Round with exact decimal `order_id` prefix.
- B012: game identity is `2350-Curupira`.
- B013: three Wilds in a column derive the main-game expanding column.
- B014: form codec and evidence-backed MD5 `signapt` verification.

B005, B006 and B007 remain UNKNOWN in the accepted handoff. Requests for their
`type/game_type` paths return an explicit error and never charge balance or create History.

Build and test:

```powershell
mvn.cmd test
mvn.cmd package
```

Run with `run-api.cmd`. The default bind is `0.0.0.0:19500`; override the port with
`CURUPIRA_API_PORT`. Demo startup has no seed setting. The optional test-only code path
may instantiate deterministic primitives inside tests, but production always constructs
`SecureRandom` directly. Build the generator dist before compiling this project.

`run-local.cmd` also starts the JDK static server on `0.0.0.0:8500` and serves only
`publish/2350-Curupira`. The publish fallback derives the API host from the visitor's
current hostname, so LAN clients do not receive a loopback API address.
