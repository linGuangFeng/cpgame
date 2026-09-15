# Beach Fun Redis pack (contract v3)

DB15 uses `cpgame:v3:1940:outcome:{WIN|LOSS}:multiplier:{integerMultiplier}` lists and win/loss sorted-set indexes. BF1 members contain one complete Round's compact printable-ASCII structural facts. The Controller claims exactly one member and fails closed when its selected outcome has no available bucket.
