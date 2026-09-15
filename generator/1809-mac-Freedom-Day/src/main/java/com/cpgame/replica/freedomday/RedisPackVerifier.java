package com.cpgame.replica.freedomday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline verifier for a generated JSONL pack; does not trust stored multiplier metadata. */
public final class RedisPackVerifier {
    private RedisPackVerifier() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("usage: RedisPackVerifier <result-pack.jsonl> [max-consecutive-wins] [max-mary-spins]");
        int cap = args.length >= 2 ? Integer.parseInt(args[1]) : 10;
        int maxMarySpins = args.length == 3 ? Integer.parseInt(args[2]) : 30;
        ObjectMapper mapper = new ObjectMapper();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        int members = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isBlank()) continue;
            JsonNode row = mapper.readTree(line);
            RoundVerification verification = codec.verify(row.path("member").asText(), cap, false, maxMarySpins);
            if (verification.multiplier().intValueExact() != row.path("ratio").asInt()) {
                throw new IllegalStateException("ratio mismatch at member " + members);
            }
            members++;
        }
        if (members == 0) throw new IllegalStateException("empty pack");
        System.out.println("REDIS_PACK_VERIFY_OK members=" + members);
    }
}
