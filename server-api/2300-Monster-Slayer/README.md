# Monster Slayer 2300 Controller — partial repair

Status: NEEDS_EVIDENCE_AND_REPAIR. This is not an accepted full-game delivery.

The Java Controller uses the shared GameRuleCore. Paid requests draw win/loss with SecureRandom, then draw an existing integer centi-multiplier bucket and atomically claim one ASCII member. Empty selected outcomes fail; they never switch outcome or fall back to local generation. Unsupported special pools fail before consuming a member. Purchase types 3/4/5 fail before any claim or wallet charge until their original wire evidence and state model are validated.

Ordinary service behavior, original captured payouts, Redis selection and failure paths are covered by the repair regression. No fresh original-page HTTP/API browser acceptance has been completed. The current publish index is documented as a borrowed family entry and is not accepted as the original 2300 entry.

Contract v3 entry: demo-controller.properties, dist/controller.jar and dist/controller.properties. The platform must inject --port or PORT in 50000–59999 and may inject --publish. Relative configured publish paths resolve from the JAR directory. No extra listener or child process is created by the Controller.

Offline repeatable local build (JDK 17+ and cached dependencies required):
`python3 build-repair.py --java-home <JDK directory>`
The script compiles production Java and tests, runs JUnit and the independent 10,000-normal-Round oracle, and packages both game JARs. It does not modify Redis or the Maven cache. The original artifacts remain in each module's target/repair-20260908 directory.

Evidence: reports/2300-Monster-Slayer/ordinary-repair-validation-20260908.json, java-build-repair-20260908.json, redis-readonly-repair-20260908.json and current-status.json.
