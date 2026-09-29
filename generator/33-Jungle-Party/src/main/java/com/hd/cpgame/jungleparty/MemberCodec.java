package com.hd.cpgame.jungleparty;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Direct compact ASCII member. Legacy JSON/Base64 Java serialization is read-only. */
public final class MemberCodec {
    private static final String PREFIX = "JP33A2|";
    private static final String LEGACY_PREFIX = "{\"schemaVersion\":1,\"rawGameId\":33,\"rulesHash\":\""
        + GameRuleCore.RULES_HASH + "\",\"payload\":\"";
    private static final String SYMBOL_CODES = "0123456789ABC";
    private static final int DELIVERY_SIZE = 24;
    private static final SecureRandom LOSS_RANDOM = new SecureRandom();

    private MemberCodec() {}

    public static String encode(GameRuleCore.Round round) {
        IndependentVerifier.Verification result = IndependentVerifier.verify(round);
        if (!result.pass()) throw new IllegalArgumentException("cannot encode invalid Round: " + result.errors());
        if (compactableLoss(round)) return "#";
        StringBuilder out = new StringBuilder(PREFIX)
            .append(round.betSize().stripTrailingZeros().toPlainString()).append('|')
            .append(round.betLevel()).append('|').append(scenarioCode(round.scenario())).append('|');
        for (GameRuleCore.Delivery delivery : round.deliveries()) {
            out.append(Character.forDigit(delivery.gameType(), 36))
                .append(Character.forDigit(delivery.smallGameType(), 36));
            appendBase36(out, delivery.fsn());
            appendBase36(out, delivery.nfsc());
            appendBase36(out, delivery.rpx());
            out.append(delivery.terminal() ? '1' : '0');
            for (GameRuleCore.Symbol symbol : delivery.board().cells()) {
                out.append(SYMBOL_CODES.charAt(symbol.ordinal()));
            }
        }
        return out.toString();
    }

    public static GameRuleCore.Round decode(String member) {
        if ("#".equals(member)) {
            return GameRuleCore.generate(LOSS_RANDOM, GameRuleCore.Scenario.ORDINARY_LOSS,
                1, new BigDecimal("0.02"));
        }
        if (member != null && member.startsWith(PREFIX)) return decodeAscii(member);
        if (member != null && member.startsWith(LEGACY_PREFIX)) return decodeLegacy(member);
        throw new IllegalArgumentException("invalid gid33 member");
    }

    private static GameRuleCore.Round decodeAscii(String member) {
        try {
            String[] header = member.split("\\|", 5);
            if (header.length != 5 || !"JP33A2".equals(header[0])) throw new IllegalArgumentException("invalid JP33A2 header");
            BigDecimal betSize = new BigDecimal(header[1]);
            int betLevel = Integer.parseInt(header[2]);
            GameRuleCore.Scenario scenario = scenario(header[3]);
            String payload = header[4];
            if (payload.isEmpty() || payload.length() % DELIVERY_SIZE != 0) {
                throw new IllegalArgumentException("invalid JP33A2 delivery payload");
            }
            BigDecimal paidBet = money(betSize.multiply(BigDecimal.valueOf(betLevel * GameRuleCore.PAYLINES)));
            BigDecimal cumulative = BigDecimal.ZERO;
            List<GameRuleCore.Delivery> deliveries = new ArrayList<>(payload.length() / DELIVERY_SIZE);
            for (int offset = 0, index = 0; offset < payload.length(); offset += DELIVERY_SIZE, index++) {
                int gt = digit(payload.charAt(offset));
                int sgt = digit(payload.charAt(offset + 1));
                int fsn = base36(payload, offset + 2);
                int nfsc = base36(payload, offset + 4);
                int rpx = base36(payload, offset + 6);
                char terminalCode = payload.charAt(offset + 8);
                if (terminalCode != '0' && terminalCode != '1') throw new IllegalArgumentException("invalid terminal flag");
                GameRuleCore.Symbol[] cells = new GameRuleCore.Symbol[GameRuleCore.REELS * GameRuleCore.ROWS];
                for (int cell = 0; cell < cells.length; cell++) {
                    int symbol = SYMBOL_CODES.indexOf(payload.charAt(offset + 9 + cell));
                    if (symbol < 0 || symbol >= GameRuleCore.Symbol.values().length) {
                        throw new IllegalArgumentException("invalid symbol code");
                    }
                    cells[cell] = GameRuleCore.Symbol.values()[symbol];
                }
                GameRuleCore.Board board = new GameRuleCore.Board(cells);
                GameRuleCore.Evaluation evaluation = GameRuleCore.evaluate(board, betLevel, betSize, rpx);
                cumulative = cumulative.add(evaluation.award());
                deliveries.add(new GameRuleCore.Delivery(index, index == 0 ? paidBet : BigDecimal.ZERO,
                    gt, sgt, fsn, nfsc, rpx, board, evaluation.wins(), evaluation.award(),
                    money(cumulative), terminalCode == '1'));
            }
            GameRuleCore.Round round = new GameRuleCore.Round(GameRuleCore.RAW_GAME_ID, scenario,
                betLevel, betSize, paidBet, money(cumulative), List.copyOf(deliveries));
            IndependentVerifier.Verification verification = IndependentVerifier.verify(round);
            if (!verification.pass()) throw new IllegalArgumentException("decoded Round failed verification: " + verification.errors());
            return round;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid JP33A2 number", error);
        }
    }

    /** Compatibility only; new writes never serialize Java objects or emit JSON/Base64. */
    private static GameRuleCore.Round decodeLegacy(String member) {
        if (!member.endsWith("\"}")) throw new IllegalArgumentException("invalid legacy gid33 envelope");
        String payload = member.substring(LEGACY_PREFIX.length(), member.length() - 2);
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payload.getBytes(StandardCharsets.US_ASCII));
            Object value;
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                value = input.readObject();
            }
            if (!(value instanceof GameRuleCore.Round round)) throw new IllegalArgumentException("payload is not a Round");
            IndependentVerifier.Verification result = IndependentVerifier.verify(round);
            if (!result.pass()) throw new IllegalArgumentException("decoded Round failed verification: " + result.errors());
            return round;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("legacy member decode failed", exception);
        }
    }

    private static boolean compactableLoss(GameRuleCore.Round round) {
        if (round.scenario() != GameRuleCore.Scenario.ORDINARY_LOSS || round.totalAward().signum() != 0
            || round.deliveries().size() != 1) return false;
        GameRuleCore.Delivery delivery = round.deliveries().getFirst();
        long scatters = delivery.board().externalCells().stream().filter("Scat"::equals).count();
        return delivery.terminal() && delivery.gameType() == 1 && delivery.smallGameType() == 0
            && delivery.fsn() == 0 && delivery.nfsc() == 0 && delivery.rpx() == 0
            && delivery.award().signum() == 0 && scatters < 3;
    }

    private static char scenarioCode(GameRuleCore.Scenario scenario) {
        return switch (scenario) {
            case ORDINARY_LOSS -> 'L';
            case ORDINARY_WIN -> 'W';
            case SCATTER_FREE_ROUNDS -> 'F';
            case RANDOM -> throw new IllegalArgumentException("resolved Round cannot retain RANDOM scenario");
        };
    }

    private static GameRuleCore.Scenario scenario(String code) {
        return switch (code) {
            case "L" -> GameRuleCore.Scenario.ORDINARY_LOSS;
            case "W" -> GameRuleCore.Scenario.ORDINARY_WIN;
            case "F" -> GameRuleCore.Scenario.SCATTER_FREE_ROUNDS;
            default -> throw new IllegalArgumentException("invalid scenario code");
        };
    }

    private static void appendBase36(StringBuilder out, int value) {
        if (value < 0 || value >= 36 * 36) throw new IllegalArgumentException("state value out of range");
        out.append(Character.forDigit(value / 36, 36)).append(Character.forDigit(value % 36, 36));
    }

    private static int base36(String value, int offset) {
        int high = digit(value.charAt(offset));
        int low = digit(value.charAt(offset + 1));
        return high * 36 + low;
    }

    private static int digit(char value) {
        int result = Character.digit(value, 36);
        if (result < 0) throw new IllegalArgumentException("invalid Base36 value");
        return result;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
