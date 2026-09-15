# 1670 Christmas Gift generator

This v40-validated artifact reconstructs complete 3x3 ordinary rounds and complete Christmas Gift lock-respin rounds from accepted provider evidence. `GameRuleCore` is the sole rules implementation used by both this loader and the local controller.

The executable distribution contains exactly one fat JAR, `generator.properties`, and `run-generator.cmd`. The loader validates every candidate through `ResultUtil`, stores only compact round facts, then atomically replaces only game 1670's Redis indexes and lists. A successful load retains exactly 9,000 ordinary and 1,000 feature rounds; it never appends to or rotates an older corpus.
