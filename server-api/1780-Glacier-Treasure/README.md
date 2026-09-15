# Glacier Treasure deterministic rule verification

Run `sh build-and-validate.sh` with JDK 21. No external libraries or network access are required.

`GameRuleCore` owns deterministic payout rules. `ResultUtil` independently enumerates matching paths using the original Init paytable and checks retained-symbol cascade transitions. `HistoryRegression` reads original fixtures only as offline test input; the validation JAR is not a Demo Controller or Redis Loader.

Current scope excludes stochastic generation, full free/purchase protocol transport, API hosting and Redis loading pending the existing spin corpus. No production fallback or replay path exists.
