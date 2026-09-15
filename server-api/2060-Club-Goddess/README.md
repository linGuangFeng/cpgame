# 2060 Club Goddess Java API

Spring Boot API for accepted `2060-protocol-r2`. Build with `mvn package`. Contract v3 starts `dist/controller.jar` as one managed process and injects `--port 50000-59999`; `--config` and `--publish` are also supported. Session, balance, round key, delivery index, idempotency and grouped History are persisted.

Spin runtime only atomically claims compact complete-Round members from Redis `192.168.10.3:6379/15`; it never reads fixtures/captures and never deals a board. Free Spins and Wild multiplier progression are supported. Buy Feature remains absent.
