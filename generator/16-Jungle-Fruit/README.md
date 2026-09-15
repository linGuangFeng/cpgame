# Jungle Fruit formal Loader

Requires JDK 21+. From any directory set JAVA_HOME to JDK 21 and run this game's build-jars.py; it builds the shared GameRuleCore into both standalone JARs. No Maven, game fixtures, captures, or full-Round replay are used at runtime.

Windows: dist/start-loader.cmd (interactive pause) or dist/start-loader.cmd --no-pause (propagates the Java exit code). The script locates dist/generator.properties relative to itself. Windows execution has not been tested on this macOS host.

Portable launch: java -jar dist/jungle-fruit-loader.jar --config=/absolute/path/to/dist/generator.properties

Configuration fixes Redis 192.168.10.3:6379 DB15, empirical-v1 namespace, 400 complete members per batch, 500 maximum per integer multiplier pool, LOSS/MARY/FREE, no seed. MARY denotes ordinary non-free paying tumbles. Fractional payout/paidBet candidates are rejected rather than floored.

Model reproduction: build-empirical-model.py reads the precise archived old corpus only, excludes 100 hash-selected whole Rounds and produces the weighted column model, holdout fixture and distribution report. It is an offline analysis utility, never bundled in production JARs.

Validation: ModelValidationMain independently checks 100 original holdouts and 10000 fresh SecureRandom Rounds. OriginalEvidenceAuditMain audits both raw corpora and records excluded sequences. Final reports are under reports/16-Jungle-Fruit. Published status is current-status.json; historical reports are not current acceptance.

Model construction and constraints are documented in protocol/16-Jungle-Fruit/protocol-spec.md and reports/16-Jungle-Fruit/distribution-model.json. Unknown original theoretical probabilities remain unknown.
