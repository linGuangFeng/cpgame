# 1407 Coin Master GO Java API

Spring Boot 3 / Java 21 implementation of the accepted `gid=55` protocol. Runtime code does not read captures, fixtures, JSONL response logs, or historical responses.

## Build and run

```powershell
mvn.cmd test
mvn.cmd package
java -jar target\coin-master-go-server-api-1.0.0.jar
```

The API listens on port `9500`. Durable state defaults to `data/coin-master-go-state.json` and atomically persists balance, session token, `roundKey`, claimed Round keys, `deliveryIndex`, `stepIndex`, idempotent responses, last Step, and History.

Implemented protocol endpoints are:

- `POST /cp/api/v1/auth/verify` (plus the frontend's version branch alias `/auth/session`)
- `POST /cp/api/v1/go-master/config`
- `POST /cp/api/v1/go-master/spin`
- `POST /cp/api/v1/go-master/log-list`
- `POST /cp/api/v1/go-master/log-view`
- `POST /cp/api/v1/ping`

Requests are UTF-8 `application/x-www-form-urlencoded`; responses use the accepted `{code, info, data}` JSON envelope. Balance is projected only through evidenced fields: `auth.player.balance`, `config.last.pb`, and `spin.pb`. No unsupported standalone balance endpoint is invented.

`Idempotency-Key` may be supplied on Spin. A repeated key returns the stored projection without advancing the active Round.

Production configuration has no seed and defaults to `LOSS`, the sole independently generated real-time path authorized by `runtimeIndependentLoss`. Explicit demo/test scripts (`BASE_WIN`, `FREE_SPINS`, `FREE_RETRIGGER`) generate and validate a complete Round before it is claimed once; they do not define or imitate an unevidenced production probability distribution. A deterministic `coin-master.demo-seed` is available only for demo/test reproduction and is absent from `application.yml`.

