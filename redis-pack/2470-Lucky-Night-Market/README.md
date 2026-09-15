# 2470 Lucky Night Market Redis

Loader writes ASCII `LNM43` members to `192.168.10.3:6379` db15.

- Ordinary index `PerKeyList_000002470` + `BetLog:000002470:XXXXXX`
- Feature index `MaryKeyList_000002470` + `MaryLog:000002470:XXXXXX`
- Wheel index `PreKeyList_100002470` + `PreLog:100002470:XXXXXX`
- Integer key is complete-round `totalUnits` (cash = betSize × level × units)

Run `generator/2470-Lucky-Night-Market/dist/run-generator.cmd`.
