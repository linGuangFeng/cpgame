package com.cpgame.monsterslayer.core;

import java.util.ArrayList;
import java.util.List;

/** Minimal ASCII complete-Round member. MS1 ordinary boards; MS2 buy/feature with loc/hearts/animals. */
public final class MinimalRoundFactCodec {
    private static final String PREFIX_V1 = "MS1";
    private static final String PREFIX_V2 = "MS2";

    public String encode(GameRuleCore.CompleteRound round) {
        GameRuleCore.validate(round);
        if (!round.special() && round.buyType() == 0) return encodeV1(round);
        return encodeV2(round);
    }

    private String encodeV1(GameRuleCore.CompleteRound round) {
        StringBuilder out = new StringBuilder(PREFIX_V1).append(round.special() ? 'S' : 'N').append(';');
        appendStepsV1(out, round.steps());
        return ascii(out.toString());
    }

    private String encodeV2(GameRuleCore.CompleteRound round) {
        char kind = round.buyType() != 0 ? 'B' : 'S';
        int buyDigit = switch (round.buyType()) { case 3 -> 1; case 4 -> 2; case 5 -> 3; default -> 0; };
        StringBuilder out = new StringBuilder(PREFIX_V2).append(kind).append(buyDigit).append(';');
        for (int s = 0; s < round.steps().size(); s++) {
            if (s > 0) out.append('/');
            GameRuleCore.Step step = round.steps().get(s);
            out.append(step.gameType()).append('.').append(step.nextType()).append('.');
            appendBoard10(out, step.board());
            GameRuleCore.FeatureFacts f = step.feature();
            out.append("|H");
            for (int i = 0; i < f.hearts().length; i++) {
                if (i > 0) out.append(',');
                out.append(f.hearts()[i]);
            }
            out.append("|L");
            for (int i = 0; i < f.locCell().length; i++) {
                if (i > 0) out.append(';');
                out.append(f.locCell()[i]).append(':').append(f.locId()[i]);
            }
            out.append("|A");
            for (int i = 0; i < f.bl().length; i++) {
                if (i > 0) out.append(';');
                out.append(f.bl()[i]).append('.').append(f.iu()[i]).append('.').append(f.t()[i]);
            }
            out.append("|R");
            for (int i = 0; i < f.rbs().length; i++) {
                if (i > 0) out.append(',');
                out.append(f.rbs()[i]);
            }
            out.append("|G");
            if (f.roles() != null && !f.roles().isEmpty()) out.append(f.roles());
        }
        return ascii(out.toString());
    }

    private static void appendStepsV1(StringBuilder out, List<GameRuleCore.Step> steps) {
        for (int s = 0; s < steps.size(); s++) {
            if (s > 0) out.append('/');
            GameRuleCore.Step step = steps.get(s);
            out.append(step.gameType()).append('.').append(step.nextType()).append('.');
            appendBoard(out, step.board());
        }
    }

    private static void appendBoard(StringBuilder out, int[] board) {
        for (int i = 0; i < board.length; i++) {
            if (i > 0) out.append(',');
            out.append(Integer.toString(board[i], 36).toUpperCase());
        }
    }

    public GameRuleCore.CompleteRound decode(String value) {
        if (value == null) throw new IllegalArgumentException("unknown Monster Slayer member");
        if (value.startsWith(PREFIX_V2)) return decodeV2(value);
        if (value.startsWith(PREFIX_V1)) return decodeV1(value);
        throw new IllegalArgumentException("unknown Monster Slayer member");
    }

    private GameRuleCore.CompleteRound decodeV1(String value) {
        if (value.length() < 6 || value.charAt(4) != ';') throw new IllegalArgumentException("unknown Monster Slayer member");
        boolean special = switch (value.charAt(3)) {
            case 'S' -> true;
            case 'N' -> false;
            default -> throw new IllegalArgumentException("invalid member kind");
        };
        List<GameRuleCore.Step> steps = new ArrayList<>();
        for (String encoded : value.substring(5).split("/", -1)) {
            String[] parts = encoded.split("\\.", 3);
            if (parts.length != 3) throw new IllegalArgumentException("invalid Step header");
            steps.add(new GameRuleCore.Step(parseBoard(parts[2]), Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        }
        return new GameRuleCore.CompleteRound(special, steps);
    }

    private GameRuleCore.CompleteRound decodeV2(String value) {
        if (value.length() < 6 || value.charAt(5) != ';') throw new IllegalArgumentException("unknown Monster Slayer member");
        char kind = value.charAt(3);
        int buyDigit = value.charAt(4) - '0';
        int buyType = switch (buyDigit) { case 1 -> 3; case 2 -> 4; case 3 -> 5; default -> 0; };
        boolean special = kind == 'S' || kind == 'B' || buyType != 0;
        List<GameRuleCore.Step> steps = new ArrayList<>();
        for (String encoded : value.substring(6).split("/", -1)) {
            steps.add(parseFeatureStep(encoded));
        }
        return new GameRuleCore.CompleteRound(special, buyType, steps);
    }

    public GameRuleCore.CompleteRound decodeBuyTemplate(int buyType, String encoded) {
        List<GameRuleCore.Step> steps = new ArrayList<>();
        for (String step : encoded.split("/", -1)) steps.add(parseFeatureStep(step));
        return new GameRuleCore.CompleteRound(true, buyType, steps);
    }

    public static GameRuleCore.Step parseFeatureStep(String encoded) {
        String[] main = encoded.split("\\|", -1);
        if (main.length < 1) throw new IllegalArgumentException("invalid feature step");
        String[] parts = main[0].split("\\.", 3);
        if (parts.length != 3) throw new IllegalArgumentException("invalid Step header");
        int[] hearts = new int[0], locCell = new int[0], locId = new int[0], bl = new int[0], iu = new int[0], t = new int[0], rbs = new int[0];
        String roles = "";
        for (int i = 1; i < main.length; i++) {
            String block = main[i];
            if (block.isEmpty()) continue;
            char tag = block.charAt(0);
            String body = block.substring(1);
            switch (tag) {
                case 'H' -> hearts = ints(body, ',');
                case 'L' -> {
                    if (body.isEmpty()) break;
                    String[] pairs = body.split(";", -1);
                    locCell = new int[pairs.length];
                    locId = new int[pairs.length];
                    for (int p = 0; p < pairs.length; p++) {
                        String[] kv = pairs[p].split(":", 2);
                        locCell[p] = Integer.parseInt(kv[0]);
                        locId[p] = Integer.parseInt(kv[1]);
                    }
                }
                case 'A' -> {
                    if (body.isEmpty()) break;
                    String[] rows = body.split(";", -1);
                    bl = new int[rows.length];
                    iu = new int[rows.length];
                    t = new int[rows.length];
                    for (int p = 0; p < rows.length; p++) {
                        String[] kv = rows[p].split("\\.", 3);
                        bl[p] = Integer.parseInt(kv[0]);
                        iu[p] = Integer.parseInt(kv[1]);
                        t[p] = Integer.parseInt(kv[2]);
                    }
                }
                case 'R' -> rbs = ints(body, ',');
                case 'G' -> roles = body;
                default -> { }
            }
        }
        return new GameRuleCore.Step(
                parseBoard10(parts[2]),
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                new GameRuleCore.FeatureFacts(hearts, locCell, locId, bl, iu, t, rbs, roles));
    }

    private static int[] parseBoard(String encoded) {
        return parseRadix(encoded, 36);
    }

    private static int[] parseBoard10(String encoded) {
        return parseRadix(encoded, 10);
    }

    private static void appendBoard10(StringBuilder out, int[] board) {
        for (int i = 0; i < board.length; i++) {
            if (i > 0) out.append(',');
            out.append(board[i]);
        }
    }

    private static int[] parseRadix(String encoded, int radix) {
        String[] cells = encoded.split(",", -1);
        if (cells.length != GameRuleCore.CELLS) throw new IllegalArgumentException("invalid board length");
        int[] board = new int[cells.length];
        for (int i = 0; i < board.length; i++) board[i] = Integer.parseInt(cells[i], radix);
        return board;
    }

    private static int[] ints(String body, char sep) {
        if (body == null || body.isEmpty()) return new int[0];
        String[] parts = body.split("\\" + sep, -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    private static String ascii(String value) {
        if (!value.chars().allMatch(c -> c >= 32 && c < 127) || value.startsWith("{") || value.startsWith("["))
            throw new IllegalStateException("member must be minimal ASCII");
        return value;
    }

    public ResultUtil.RoundResult verify(String value) { return ResultUtil.evaluate(decode(value)); }
    public boolean supports(String value) {
        return value != null && (value.startsWith(PREFIX_V1) || value.startsWith(PREFIX_V2));
    }
}
