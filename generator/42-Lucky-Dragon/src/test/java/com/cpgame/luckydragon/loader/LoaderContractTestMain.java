package com.cpgame.luckydragon.loader;

import com.cpgame.luckydragon.core.MinimalFactCodec;
import com.cpgame.luckydragon.core.RoundFacts;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/** Validates the delivered external configuration and the non-recomputable Redis member codec. */
public final class LoaderContractTestMain {
    public static void main(String[] args) throws Exception {
        Path config = Path.of(args.length == 0 ? "generator/42-Lucky-Dragon/dist/generator.properties" : args[0]);
        LoaderMain.LoaderConfig loaded = LoaderMain.LoaderConfig.load(config);
        if (loaded.redisGameId() != 42 || loaded.maxMembersPerMultiplier() != 300
            || loaded.normalCount() <= 0 || loaded.specialCount() <= 0
            || com.cpgame.luckydragon.core.RandomRoundGenerator.jointStateCount(loaded.jointStateModel()) != 64) {
            throw new AssertionError("delivered Loader configuration mismatch");
        }
        RoundFacts facts = new RoundFacts(new BigDecimal("0.5"), 1, List.of("WILD", "H1", "H4"), 5, "42-contract");
        MinimalFactCodec codec = new MinimalFactCodec();
        if (!facts.equals(codec.decode(codec.encode(facts)))) throw new AssertionError("fact codec mismatch");
        System.out.println("LoaderContractTestMain PASS keys-read=32 joint-states=64 codec=round-trip");
    }
}
