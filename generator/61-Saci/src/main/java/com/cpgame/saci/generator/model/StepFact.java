package com.cpgame.saci.generator.model;

import java.util.List;

/** 一个 Delivery 的不可重算事实：牌面与状态计数。金钱由 RoundFactory / ResultUtil 重算。 */
public record StepFact(
        List<String> rskl,
        List<Integer> syxl,
        List<Integer> wskl,
        List<List<Integer>> afnl,
        int smallGameType,
        int ss,
        int fsn,
        int nfsc,
        int rsn,
        int nrsc,
        int gt,
        int gm,
        int wn
) {
    public StepFact {
        rskl = List.copyOf(rskl);
        syxl = List.copyOf(syxl);
        wskl = List.copyOf(wskl);
        afnl = afnl.stream().map(List::copyOf).toList();
    }

    public boolean roundTerminal() {
        return ss == 1 && fsn == nfsc && rsn == nrsc;
    }
}
