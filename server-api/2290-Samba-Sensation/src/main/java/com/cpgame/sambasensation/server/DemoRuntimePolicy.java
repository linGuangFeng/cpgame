package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.generator.RuntimeSpinGenerator;

/** Demo调用方参数；正式接入时由配置中心构造同一Parameters，生成器本身不保存这些值。 */
final class DemoRuntimePolicy {
    private DemoRuntimePolicy() { }

    static final int[] PAYOUT_CAP_ODDS = {20, 50, 100, 200, 800};
    static final int[] PAYOUT_CAP_WEIGHTS = {20, 30, 30, 15, 5};
    static final int NATURAL_MARY_WEIGHT = 175;
    static final int ORDINARY_WEIGHT = 4725;
    static final int WIN_WEIGHT = 1;
    static final int LOSS_WEIGHT = 1;

    static RuntimeSpinGenerator.Parameters generatorParameters() {
        int[][][] paid = {
                {{1537,4425,4132,3642,3565,3583,3518,3560,3571,3592,545}},
                {{46,176,152,149,146,163,172,172,144,171,9},
                 {63,196,159,154,138,142,160,160,146,172,10}},
                {{931,4228,3958,3812,3865,3756,3736,3996,3905,3817,311},
                 {879,4404,4068,3804,3823,3865,3848,3694,3864,3880,186},
                 {962,4293,3990,3785,3875,3782,3911,3843,3849,3903,122}}
        };
        // 当前证据：不变4141、递增699，双格仅1/699；增量样本以+1为主，并出现过+2/+4。
        // 这些只是Demo调用参数，正式环境由配置中心替换。
        return new RuntimeSpinGenerator.Parameters(paid, 4141, 699, 698, 1,
                new int[]{156,166,154,43,164}, new int[]{698,1,0,1}, 20_000);
    }
}
