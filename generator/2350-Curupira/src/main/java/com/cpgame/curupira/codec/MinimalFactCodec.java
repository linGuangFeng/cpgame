package com.cpgame.curupira.codec;

import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRound;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.EntryKind;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.model.FeatureStep.Role;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 ASCII member：CU1 + 入口 + 种类 + 各 Step 牌面。
 * 不保存余额、JSON 响应、时间戳；派奖由 ResultUtil 从牌面反推。
 */
public final class MinimalFactCodec {
    private static final String PREFIX = "CU1";
    private static final ResultUtil UTIL = new ResultUtil();

    public String encode(CompleteRound round) {
        if (round == null) throw new IllegalArgumentException("完整局不能为空");
        return encodeFact(fromOrdinary(round));
    }

    public String encode(CompleteRoundFact fact) {
        return encodeFact(fact);
    }

    public String encodeFact(CompleteRoundFact fact) { return encodeFact(fact, true); }
    public String encodeFull(CompleteRoundFact fact) { return encodeFact(fact, false); }

    private String encodeFact(CompleteRoundFact fact, boolean compact) {
        if (fact == null) throw new IllegalArgumentException("完整局不能为空");
        StringBuilder out = new StringBuilder(PREFIX);
        out.append(fact.entry() == EntryKind.PAID ? 'P' : 'B');
        out.append(kindCode(fact.kind()));
        out.append(';');
        if (compact && isIndependentLoss(fact)) return out.append('#').toString();
        for (int i = 0; i < fact.steps().size(); i++) {
            if (i > 0) out.append('/');
            appendStep(out, fact.steps().get(i));
        }
        String encoded = out.toString();
        if (encoded.startsWith("{") || encoded.startsWith("[")) throw new IllegalStateException("JSON member forbidden");
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(encoded)) {
            throw new IllegalStateException("member must be ASCII");
        }
        return encoded;
    }

    public CompleteRound decodeRound(String member) {
        CompleteRoundFact fact = decode(member);
        if (!fact.kind().ordinary()) throw new IllegalArgumentException("非普通局不能按单步 CompleteRound 解码");
        return fact.asOrdinaryRound();
    }

    public CompleteRound decodeRound(byte[] member) {
        if (member == null) throw new IllegalArgumentException("Redis member 不能为空");
        return decodeRound(new String(member, StandardCharsets.US_ASCII));
    }

    public List<List<Integer>> decode(byte[] member) {
        return decodeRound(member).deliveries().stream().map(step -> step.evaluatedBoard().ps()).toList();
    }

    public CompleteRoundFact decode(String member) {
        if (member == null || !member.startsWith(PREFIX) || member.length() < 6 || member.charAt(5) != ';') {
            throw new IllegalArgumentException("unknown Curupira member version");
        }
        if (member.startsWith("{") || member.startsWith("[")) {
            throw new IllegalArgumentException("full JSON member forbidden");
        }
        EntryKind entry = switch (member.charAt(3)) {
            case 'P' -> EntryKind.PAID;
            case 'B' -> EntryKind.BUY;
            default -> throw new IllegalArgumentException("invalid entry kind");
        };
        Kind kind = parseKind(member.charAt(4), entry);
        if (member.substring(6).equals("#")) {
            if (entry != EntryKind.PAID || kind != Kind.LOSS) {
                throw new IllegalArgumentException("loss marker requires ordinary paid LOSS");
            }
            EvaluatedBoard board = LossHolder.GENERATOR.nextLoss();
            FeatureStep step = FeatureStep.symbol(Role.ORDINARY, board.ps(), board, 0, 0, 0, 1, 1);
            return new CompleteRoundFact(identityFromMember(member), kind, entry, List.of(step));
        }
        List<FeatureStep> steps = new ArrayList<>();
        for (String encoded : member.substring(6).split("/", -1)) {
            steps.add(parseStep(encoded, kind, steps.size()));
        }
        return new CompleteRoundFact(identityFromMember(member), kind, entry, steps);
    }

    public byte[] encodeBytes(CompleteRoundFact fact) {
        return encode(fact).getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean isIndependentLoss(CompleteRoundFact fact) {
        if (fact.kind() != Kind.LOSS || fact.entry() != EntryKind.PAID || fact.steps().size() != 1) return false;
        FeatureStep step = fact.steps().get(0);
        if (step.role() != Role.ORDINARY || step.st() != 0 || step.tt() != 0 || step.featureT() != 0
                || step.gt() != 1 || step.resGt() != 1 || step.redisUnits() != 0) return false;
        EvaluatedBoard board = UTIL.evaluate(step.cells());
        return board.awards().isEmpty() && board.multiplierSum() == 0
                && board.scatterCount() < GameRules.SCATTER_TRIGGER && board.expandingWildColumns().isEmpty();
    }

    private static final class LossHolder {
        private static final com.cpgame.curupira.core.ConstructiveLossGenerator GENERATOR = create();
        private static com.cpgame.curupira.core.ConstructiveLossGenerator create() {
            var policy = com.cpgame.curupira.core.GenerationPolicy.ordinaryPaidDefaults();
            var weights = new java.util.HashMap<>(policy.symbolWeights());
            // Ordinary markers cannot introduce an expanding-Wild animation or a feature trigger.
            weights.put(GameRules.WILD, 0);
            weights.put(GameRules.SCATTER, 0);
            var sampler = new com.cpgame.curupira.random.WeightedSymbolSampler(
                    new com.cpgame.curupira.random.SecureRandomSource(), weights);
            return new com.cpgame.curupira.core.ConstructiveLossGenerator(sampler,
                    new com.cpgame.curupira.core.CandidateBoardGenerator(sampler), UTIL, policy);
        }
    }

    private static CompleteRoundFact fromOrdinary(CompleteRound round) {
        EvaluatedBoard board = round.deliveries().get(0).evaluatedBoard();
        Kind kind = board.scatterCount() >= GameRules.SCATTER_TRIGGER ? Kind.TRIGGER
                : board.expandingWildColumns().isEmpty()
                ? (board.awards().isEmpty() ? Kind.LOSS : Kind.WIN)
                : Kind.EXPANDING_WILD;
        FeatureStep step = FeatureStep.symbol(Role.ORDINARY, board.ps(), board, 0, 0, 0, 1, 1);
        return new CompleteRoundFact(round.roundKey(), kind, EntryKind.PAID, List.of(step));
    }

    private static char kindCode(Kind kind) {
        return switch (kind) {
            case LOSS -> 'L';
            case WIN -> 'W';
            case EXPANDING_WILD -> 'E';
            case TRIGGER -> 'T';
            case FREE_EW, BUY_FE -> 'F';
            case HOLD, BUY_HS -> 'H';
        };
    }

    private static Kind parseKind(char code, EntryKind entry) {
        return switch (code) {
            case 'L' -> Kind.LOSS;
            case 'W' -> Kind.WIN;
            case 'E' -> Kind.EXPANDING_WILD;
            case 'T' -> Kind.TRIGGER;
            case 'F' -> entry == EntryKind.BUY ? Kind.BUY_FE : Kind.FREE_EW;
            case 'H' -> entry == EntryKind.BUY ? Kind.BUY_HS : Kind.HOLD;
            default -> throw new IllegalArgumentException("invalid kind");
        };
    }

    private static void appendStep(StringBuilder out, FeatureStep step) {
        if (step.role() == Role.HOLD) {
            out.append('C');
            for (int cell : step.cells()) out.append(Integer.toString(cell, 36).toUpperCase());
            out.append(':').append(step.st()).append(':').append(step.fcc()).append(':');
            for (int i = 0; i < step.fcn().size(); i++) {
                if (i > 0) out.append('.');
                out.append(step.fcn().get(i));
            }
            out.append(':').append(step.fcnw());
            return;
        }
        out.append('S');
        for (int cell : step.cells()) out.append(symbolChar(cell));
    }

    private static FeatureStep parseStep(String encoded, Kind kind, int index) {
        if (encoded.isEmpty()) throw new IllegalArgumentException("empty step");
        if (encoded.charAt(0) == 'C') return parseHold(encoded, index);
        if (encoded.charAt(0) != 'S' || encoded.length() != 16) {
            throw new IllegalArgumentException("invalid symbol step");
        }
        List<Integer> cells = new ArrayList<>(15);
        for (int i = 1; i < 16; i++) cells.add(symbolId(encoded.charAt(i)));
        EvaluatedBoard evaluated = UTIL.evaluate(cells);
        Role role = switch (kind) {
            case TRIGGER -> Role.TRIGGER;
            case FREE_EW, BUY_FE -> Role.FREE_EW;
            default -> Role.ORDINARY;
        };
        int st = 0, tt = 0, featureT = 0, gt = 1, resGt = 1;
        if (role == Role.TRIGGER) {
            st = 1; tt = 1; featureT = 1;
        } else if (role == Role.FREE_EW) {
            st = GameRules.FREE_EXPANDING_WILD_COUNT - 1 - index;
            tt = GameRules.FREE_EXPANDING_WILD_COUNT;
            featureT = 2;
            gt = index == 0 ? 2 : 1;
            resGt = 2;
        }
        return FeatureStep.symbol(role, cells, evaluated, st, tt, featureT, gt, resGt);
    }

    private static FeatureStep parseHold(String encoded, int index) {
        String[] parts = encoded.split(":", -1);
        if (parts.length != 5 || parts[0].length() != 16) throw new IllegalArgumentException("invalid hold step");
        List<Integer> cells = new ArrayList<>(15);
        for (int i = 1; i < 16; i++) cells.add(Integer.parseInt(parts[0].substring(i, i + 1), 36));
        int st = Integer.parseInt(parts[1]);
        int fcc = Integer.parseInt(parts[2]);
        List<Integer> fcn = new ArrayList<>();
        if (!parts[3].isEmpty()) {
            for (String item : parts[3].split("\\.", -1)) fcn.add(Integer.parseInt(item));
        }
        int fcnw = Integer.parseInt(parts[4]);
        int gt = index == 0 ? 3 : 1;
        return FeatureStep.hold(cells, st, GameRules.HOLD_START_SPINS, fcc, fcnw, fcn, cells, gt,
                fcnw * GameRules.PAYLINE_COUNT);
    }

    private static char symbolChar(int id) {
        return switch (id) {
            case 1 -> '1'; case 2 -> '2'; case 3 -> '3'; case 4 -> '4';
            case 11 -> 'A'; case 12 -> 'K'; case 13 -> 'Q'; case 14 -> 'J';
            case 21 -> 'W'; case 31 -> 'S';
            default -> throw new IllegalArgumentException("未知符号：" + id);
        };
    }

    private static int symbolId(char code) {
        return switch (code) {
            case '1' -> 1; case '2' -> 2; case '3' -> 3; case '4' -> 4;
            case 'A' -> 11; case 'K' -> 12; case 'Q' -> 13; case 'J' -> 14;
            case 'W' -> 21; case 'S' -> 31;
            default -> throw new IllegalArgumentException("未知符号码：" + code);
        };
    }

    /** member 本身不含 roundKey；运行时投影时再分配。解码用稳定正数占位。 */
    private static long identityFromMember(String member) {
        long hash = 0xC0FFEE2350L;
        for (int i = 0; i < member.length(); i++) hash = hash * 131L + member.charAt(i);
        long id = Math.abs(hash);
        if (id <= 9_007_199_254_740_991L) id += 10_000_000_000_000_000L;
        return id;
    }
}
