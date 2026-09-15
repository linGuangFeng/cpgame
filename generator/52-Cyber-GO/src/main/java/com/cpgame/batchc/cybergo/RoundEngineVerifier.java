package com.cpgame.batchc.cybergo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.Map;

/** 独立运行验证：不使用fixture作为候选池，仅用规则反推生成结果。 */
public final class RoundEngineVerifier {
    private RoundEngineVerifier() { }

    public static Summary verify(GenerationLimits limits, int roundCount, int lossSamples,
                                 BigDecimal minimumFirstSuccessRate) {
        if (roundCount < 1) throw new IllegalArgumentException("完整Round验证数量必须大于0");
        if (lossSamples < 1) throw new IllegalArgumentException("LOSS验证样本量必须大于0");
        if (minimumFirstSuccessRate.signum() < 0 || minimumFirstSuccessRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("最低首次成功率必须在0..1之间");
        }

        CompleteRoundVerifier verifier = new CompleteRoundVerifier(limits);
        MinimalFactCodec codec = new MinimalFactCodec();
        GameRuleCore core = new GameRuleCore(new SecureRandom(), limits);
        Map<CyberGoModels.RoundKind, Integer> kinds = new EnumMap<>(CyberGoModels.RoundKind.class);
        MessageDigest generatedFactsDigest = sha256();
        for (int index = 0; index < roundCount; index++) {
            CyberGoModels.CompleteRound generated = core.generateCompleteRound();
            CompleteRoundVerifier.Verification actual = verifier.verify(generated);
            kinds.merge(actual.inferredKind(), 1, Integer::sum);

            CyberGoModels.MinimalRoundFacts facts = codec.extract(generated);
            updateDigest(generatedFactsDigest, facts);
            CyberGoModels.CompleteRound rebuilt = core.rebuild(facts);
            CompleteRoundVerifier.Verification restored = verifier.verify(rebuilt);
            if (actual.inferredKind() != restored.inferredKind()
                    || actual.totalWin().compareTo(restored.totalWin()) != 0
                    || actual.deliveryCount() != restored.deliveryCount()) {
                throw new IllegalStateException("最小盘面事实无法无损重建规则结果");
            }
        }

        RandomCandidateGenerator lossCandidates = new RandomCandidateGenerator(new SecureRandom());
        int firstSuccesses = 0;
        for (int index = 0; index < lossSamples; index++) {
            ResultUtil.Evaluation evaluation = ResultUtil.evaluate(lossCandidates.paidBoardCandidate(),
                    CyberGoRules.MINIMUM_BET_LEVEL, CyberGoRules.MINIMUM_BET_SIZE);
            if (evaluation.isLoss()) firstSuccesses++;
        }
        BigDecimal firstSuccessRate = BigDecimal.valueOf(firstSuccesses)
                .divide(BigDecimal.valueOf(lossSamples), 8, RoundingMode.HALF_UP);
        if (firstSuccessRate.compareTo(minimumFirstSuccessRate) < 0) {
            throw new IllegalStateException("自然候选LOSS比例低于调用者下限");
        }
        return new Summary(roundCount, Map.copyOf(kinds), lossSamples, firstSuccessRate,
                codec.redisEncodingEnabled(), CyberGoRules.RULES_HASH,
                java.util.HexFormat.of().formatHex(generatedFactsDigest.digest()));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void updateDigest(MessageDigest digest, CyberGoModels.MinimalRoundFacts facts) {
        for (var board : facts.boards()) {
            for (String symbol : board) {
                digest.update(symbol.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            digest.update((byte) 0xff);
        }
        digest.update((byte) 0xfe);
    }

    public record Summary(int verifiedRounds, Map<CyberGoModels.RoundKind, Integer> kinds,
                          int lossSamples, BigDecimal lossFirstSuccessRate,
                          boolean redisEncodingEnabled, String rulesHash,
                          String generatedFactsSha256) { }
}
