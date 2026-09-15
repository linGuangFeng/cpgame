# Beach Fun contract v3

The canonical rules document is `rules-core.json`; its SHA-256 binding is `sha256:4332cba4e18f7a2dfb8c99d2c5f8969e7650edc3d109f97204af38fb4d1a2af2`.

One paid request claims one complete BF2 Round member from Redis DB15. The Controller selects a WIN or LOSS index, then an available integer-multiplier bucket, and reads one member from `BetLog:000001940:{n}` or `MaryLog:000001940:{n}`. Cascades are projected inside `props[]`; free deliveries reuse the same member until `frees.surplus_times=0`. No fixture and no runtime deal is permitted. Missing Redis data fails with HTTP 503.
