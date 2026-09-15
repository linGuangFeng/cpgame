# 1407-mac Coin Master GO Redis pack

This pack documents the platform-side downstream Redis contract for raw game ID `1407`.
It does not claim that the key names, adjustable symbol weights, or selection distribution
were observed from the provider. Runtime members contain only `CMG1` minimal complete-Round
facts; the Java loader independently rebuilds and verifies every member before a single
`MULTI/EXEC` batch performs `ZADD`, `RPUSH`, and `LTRIM`.

The executable delivery is `generator/1407-mac-Coin-Master-GO/dist/` and contains exactly
the shaded JAR, UTF-8 configuration, and self-locating GBK Windows launcher.
