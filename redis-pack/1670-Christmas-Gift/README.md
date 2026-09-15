# 1670 Christmas Gift Redis pack

The v40-validated Java loader atomically replaces game 1670's complete-round cache at `192.168.10.3:6379`, database 15. It writes `PerKeyList_000001670`/`BetLog:000001670:*` for ordinary rounds and `MaryKeyList_000001670`/`MaryLog:000001670:*` for Christmas Gift feature rounds.

Runtime selection consumes one verified complete round with `LPOP`; it does not read fixtures, generate a board at request time, rotate scripted scenarios, or fall back when a requested pool is empty.
