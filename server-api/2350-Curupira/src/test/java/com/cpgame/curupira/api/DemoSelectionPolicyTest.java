package com.cpgame.curupira.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class DemoSelectionPolicyTest {
    @Test
    void everyConfiguredBandUsesTheDocumentedRedisMultiplierRange() {
        int[][] ranges = {{0, 0}, {1, 125}, {126, 500}, {501, 1_250}, {1_251, 2_500}, {2_501, 250_000}};
        for (int selected = 0; selected < ranges.length; selected++) {
            int[] weights = new int[6];
            weights[selected] = 1;
            DemoSelectionPolicy policy = new DemoSelectionPolicy(weights, new Random(2350L + selected));
            for (int draw = 0; draw < 100; draw++) {
                assertThat(policy.chooseTargetMultiplier()).isBetween(ranges[selected][0], ranges[selected][1]);
            }
        }
    }

    @Test
    void weightsMustBeExplicitNonNegativeAndNotAllZero() {
        assertThatThrownBy(() -> new DemoSelectionPolicy(new int[]{0, 0, 0, 0, 0, 0}, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoSelectionPolicy(new int[]{1, -1, 0, 0, 0, 0}, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
