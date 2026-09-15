package com.cpgame.beachfun.api;

import com.cpgame.beachfun.core.GameRuleCore;

/** Entry must render without a Redis result pool and must never charge or award a win. */
public final class IdleEntryTestMain {
    public static void main(String[] args) {
        var rules = new GameRuleCore();
        var session = new SessionState();
        double balance = session.balance;
        for (int i = 0; i < 10; i++) {
            var board = ControllerMain.createIdleBoard();
            if (board.free() || board.awardedFreeSpins() != 0 || board.cascades().size() != 1)
                throw new AssertionError("idle board starts a feature");
            var first = board.cascades().get(0);
            if (!rules.evaluate(first.board(), 1).isEmpty()) throw new AssertionError("idle board wins");
            var payload = new ProtocolProjection().idle(session, board);
            if (session.balance != balance || session.active != null || !session.history.isEmpty())
                throw new AssertionError("entry changes wager state");
            if (((Number) payload.get("total_win")).doubleValue() != 0
                    || ((Number) payload.get("change_gold")).doubleValue() != 0)
                throw new AssertionError("entry charges or awards money");
        }
        System.out.println("PASS: ten legal idle boards, no Redis dependency and no balance/history changes");
    }
}
