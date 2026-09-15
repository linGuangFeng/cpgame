# Jungle Party local controller

This Java controller serves the 151-file observed Jungle Party frontend and the confirmed raw-gid-33 API from one platform-managed process. The platform must inject one port in `50000..59999` through `--port` or `PORT`; no second listener or child server is started.

The API implements auth/session, config with resumable `last`, live Java-generated spin results, per-session balance, idempotent replay, durable mid-free-round resume, one-row-per-complete-Round history, and Redis complete-Round consumption when `result.source=redis` is selected. The default `direct` mode uses the same verified Java rule core at runtime and never rotates fixtures or contacts the origin.

Build the generator with `mvn install`, then run `mvn package` here. The shaded platform artifact is `dist/controller.jar`; `dist/demo-controller.properties` is the Controller v3 launch contract.
