package com.cpgame.curupira.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.redis.RedisContractGate;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisRoundStoreTest {
    private final MinimalFactCodec codec = new MinimalFactCodec();
    private final RedisContractGate keys = new RedisContractGate();

    @Test
    void searchesDownwardAndSkipsLegacyMarkerWithoutGenerating() {
        String valid = "CU1PW;S111111111111111";
        int multiplier = codec.decode(valid).redisMultiplier();
        FakeRedis redis = new FakeRedis(List.of(multiplier + 1, multiplier));
        redis.put(keys.listFor(CompleteRoundFact.Kind.WIN, multiplier + 1, 2350), List.of("CU1PL;#"));
        redis.put(keys.listFor(CompleteRoundFact.Kind.WIN, multiplier, 2350), List.of(valid));

        CompleteRoundFact claimed = new RedisRoundStore(redis, 2350)
                .claimPaidAtOrBelow(multiplier + 10);

        assertThat(claimed.redisMultiplier()).isEqualTo(multiplier);
        assertThat(codec.encode(claimed)).isEqualTo(valid);
    }

    @Test
    void peekLossRequiresAFullZeroMultiplierMember() {
        String loss = "CU1PL;S111222333444AAA";
        FakeRedis redis = new FakeRedis(List.of(0));
        redis.put(keys.listFor(CompleteRoundFact.Kind.LOSS, 0, 2350), List.of("CU1PL;#", loss));

        CompleteRoundFact claimed = new RedisRoundStore(redis, 2350).peekLoss();

        assertThat(claimed.kind()).isEqualTo(CompleteRoundFact.Kind.LOSS);
        assertThat(codec.encode(claimed)).isEqualTo(loss);
    }

    @Test
    void emptyCacheIsAnErrorAndHasNoFallback() {
        RedisRoundStore store = new RedisRoundStore(new FakeRedis(List.of()), 2350);
        assertThatThrownBy(() -> store.claimPaidAtOrBelow(125))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache unavailable or empty")
                .hasMessageNotContaining("18.234")
                .hasMessageNotContaining("password");
    }

    @Test
    void readsFreeAndHoldFromTheirSeparateMaryFamilies() {
        GameRuleCore core = new GameRuleCore();
        for (CompleteRoundFact fact : List.of(
                core.generateFreeExpandingWildCandidate(), core.generateHoldAndSpinsCandidate())) {
            String member = codec.encode(fact);
            int multiplier = fact.redisMultiplier();
            FakeRedis redis = new FakeRedis(List.of(multiplier));
            redis.put(keys.listFor(fact.kind(), multiplier, 2350), List.of(member));

            CompleteRoundFact claimed = new RedisRoundStore(redis, 2350)
                    .claimMaryAtOrBelow(fact.kind(), multiplier);

            assertThat(codec.encode(claimed)).isEqualTo(member);
            assertThat(keys.indexFor(claimed.kind(), 2350)).startsWith("MaryKeyList_");
        }
    }

    private static final class FakeRedis implements RedisCommands {
        private final List<Integer> buckets;
        private final Map<String, List<String>> lists = new HashMap<>();

        private FakeRedis(List<Integer> buckets) { this.buckets = buckets; }
        private void put(String key, List<String> members) { lists.put(key, members); }

        @Override
        public Object command(String... args) {
            return switch (args[0]) {
                case "ZREVRANGEBYSCORE" -> buckets.stream()
                        .filter(value -> value <= Integer.parseInt(args[2]) && value >= Integer.parseInt(args[3]))
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(value -> Integer.toString(value).getBytes(StandardCharsets.UTF_8)).toList();
                case "LLEN" -> (long) lists.getOrDefault(args[1], List.of()).size();
                case "LINDEX" -> {
                    List<String> members = lists.getOrDefault(args[1], List.of());
                    int index = Integer.parseInt(args[2]);
                    yield index >= 0 && index < members.size()
                            ? members.get(index).getBytes(StandardCharsets.US_ASCII) : null;
                }
                default -> throw new AssertionError("unexpected command " + args[0]);
            };
        }

        @Override public void close() { }
    }
}
