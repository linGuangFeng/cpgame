# 1780 Glacier Treasure Redis

- Host 192.168.10.3 port 6379 database 15
- Keys: `PerKeyList_000001780`, `BetLog:000001780:RRRRRR`, `MaryKeyList_000001780`, `MaryLog:000001780:RRRRRR`
- Members are compact ASCII (not JSON). Ratio 0 is the loss pool.
- Loader: `generator/1780-Glacier-Treasure/dist/start-redis-loader.cmd`
- Do not FLUSHDB. Own game id 1780 only.
