package com.cpgame.batcha.g8;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Replay origin complete paid Rounds through GameRuleCore / ResultUtil. */
public final class CaptureOracleVerifier {
    private CaptureOracleVerifier() { }

    public static void main(String[] args) throws Exception {
        Result report = verify();
        System.out.printf("origin-oracle rounds=%d steps=%d payMismatch=%d clusterMismatch=%d materializeFail=%d%n",
            report.rounds(), report.steps(), report.payMismatch(), report.clusterMismatch(), report.materializeFail());
        if (report.payMismatch() != 0 || report.clusterMismatch() != 0 || report.materializeFail() != 0) {
            for (String issue : report.issues()) System.err.println(issue);
            System.exit(2);
        }
    }

    public static Result verify() throws Exception {
        ResultUtil resultUtil = new ResultUtil();
        int rounds = 0, steps = 0, pay = 0, cluster = 0, materialize = 0;
        List<String> issues = new ArrayList<>();
        try (InputStream in = CaptureOracleVerifier.class.getResourceAsStream("/origin-complete-rounds.jsonl");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                OriginRound origin = OriginRound.parse(line);
                rounds++;
                BigDecimal betSize = new BigDecimal("0.05");
                int betLevel = 4;
                BigDecimal paid = GameRuleCore.paidBet(betSize, betLevel);
                List<Step> facts = new ArrayList<>();
                for (OriginStep step : origin.steps) {
                    steps++;
                    List<String> board = step.board;
                    GameRuleCore.BoardResult core = GameRuleCore.evaluateBoard(board, betSize, betLevel);
                    ResultUtil.BoardResult independent = resultUtil.evaluate(board, betSize, betLevel);
                    if (core.winAmount().compareTo(independent.winAmount()) != 0) {
                        pay++;
                        if (issues.size() < 20) issues.add(origin.dir + " core/resultutil pay");
                    }
                    if (core.winAmount().compareTo(step.win) != 0) {
                        pay++;
                        if (issues.size() < 20) {
                            issues.add(origin.dir + " pay got " + core.winAmount() + " want " + step.win
                                + " symbols=" + step.symbols);
                        }
                    }
                    if (!resultUtil.sameMatches(independent.matches(), core.matches())) {
                        cluster++;
                        if (issues.size() < 20) issues.add(origin.dir + " independent cluster mismatch");
                    }
                    facts.add(Step.fact(facts.size(), facts.isEmpty() ? paid : BigDecimal.ZERO, betSize, betLevel,
                        board, step.extra, step.st, step.sg, step.rs));
                }
                try {
                    CompleteRound round = GameRuleCore.materialize(paid, betSize, betLevel, facts);
                    BigDecimal originPayout = origin.steps.getLast().sum;
                    if (round.payout().compareTo(originPayout) != 0) {
                        pay++;
                        if (issues.size() < 20) {
                            issues.add(origin.dir + " payout " + round.payout() + " vs " + originPayout);
                        }
                    }
                } catch (RuntimeException failure) {
                    materialize++;
                    if (issues.size() < 20) issues.add(origin.dir + " materialize " + failure.getMessage());
                }
            }
        }
        return new Result(rounds, steps, pay, cluster, materialize, issues);
    }

    public record Result(int rounds, int steps, int payMismatch, int clusterMismatch, int materializeFail,
                         List<String> issues) { }

    private static final class OriginRound {
        private final String dir;
        private final List<OriginStep> steps;

        private OriginRound(String dir, List<OriginStep> steps) {
            this.dir = dir;
            this.steps = steps;
        }

        static OriginRound parse(String json) {
            String dir = stringField(json, "dir");
            List<OriginStep> steps = new ArrayList<>();
            int stepsAt = json.indexOf("\"steps\":");
            String body = json.substring(stepsAt);
            int index = 0;
            while (true) {
                int boardAt = body.indexOf("\"board\":", index);
                if (boardAt < 0) break;
                List<String> board = stringArray(body, boardAt);
                List<ExtraCell> extra = extraArray(body, body.indexOf("\"extra\":", boardAt));
                int st = intField(body, "st", boardAt);
                int sg = intField(body, "sg", boardAt);
                int rs = intField(body, "rs", boardAt);
                BigDecimal win = decimalField(body, "win", boardAt);
                BigDecimal sum = decimalField(body, "sum", boardAt);
                List<String> symbols = stringArrayAfter(body, "\"symbols\":", boardAt);
                steps.add(new OriginStep(board, extra, st, sg, rs, win, sum, symbols));
                index = boardAt + 8;
            }
            if (steps.isEmpty()) throw new IllegalArgumentException("no steps in " + dir);
            return new OriginRound(dir, steps);
        }
    }

    private record OriginStep(List<String> board, List<ExtraCell> extra, int st, int sg, int rs,
                              BigDecimal win, BigDecimal sum, List<String> symbols) { }

    private static String stringField(String json, String name) {
        int at = json.indexOf("\"" + name + "\":");
        int start = json.indexOf('"', at + name.length() + 3) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static int intField(String json, String name, int from) {
        int at = json.indexOf("\"" + name + "\":", from);
        int start = at + name.length() + 3;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Integer.parseInt(json.substring(start, end).trim());
    }

    private static BigDecimal decimalField(String json, String name, int from) {
        int at = json.indexOf("\"" + name + "\":", from);
        int start = at + name.length() + 3;
        int end = start;
        while (end < json.length() && "0123456789.-".indexOf(json.charAt(end)) >= 0) end++;
        return new BigDecimal(json.substring(start, end).trim());
    }

    private static List<String> stringArrayAfter(String json, String key, int from) {
        int at = json.indexOf(key, from);
        if (at < 0) return List.of();
        return stringArray(json, at);
    }

    private static List<String> stringArray(String json, int from) {
        int start = json.indexOf('[', from);
        int end = json.indexOf(']', start);
        String body = json.substring(start + 1, end);
        List<String> values = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            int q = body.indexOf('"', i);
            if (q < 0) break;
            int q2 = body.indexOf('"', q + 1);
            values.add(body.substring(q + 1, q2));
            i = q2 + 1;
        }
        return values;
    }

    private static List<ExtraCell> extraArray(String json, int from) {
        if (from < 0) return List.of();
        int start = json.indexOf('[', from);
        int depth = 0;
        int end = start;
        for (; end < json.length(); end++) {
            char c = json.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { end++; break; }
            }
        }
        String body = json.substring(start, end);
        List<ExtraCell> extra = new ArrayList<>();
        int i = 0;
        while (true) {
            int open = body.indexOf('[', i + 1);
            if (open < 0) break;
            int comma = body.indexOf(',', open);
            int close = body.indexOf(']', comma);
            if (comma < 0 || close < 0) break;
            int coord = Integer.parseInt(body.substring(open + 1, comma).trim());
            int q1 = body.indexOf('"', comma);
            int q2 = body.indexOf('"', q1 + 1);
            extra.add(new ExtraCell(coord, body.substring(q1 + 1, q2)));
            i = close;
        }
        return extra;
    }
}
