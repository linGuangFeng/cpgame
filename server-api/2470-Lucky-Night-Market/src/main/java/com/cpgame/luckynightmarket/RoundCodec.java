package com.cpgame.luckynightmarket;

import java.util.ArrayList;
import java.util.List;

/**
 * ASCII facts only: LNM1|mode|9-symbol-digits.3-base36-muls.base36-wheel[;step...].
 * L/B/W/F means ordinary loss / ordinary win / wheel / feature. No money, result JSON,
 * protocol status, cumulative totals or duplicated win lists are stored in a member.
 */
public final class RoundCodec {
    public static final String VERSION = "LNM1";
    private RoundCodec() {}

    public static String encode(RoundFact round) {
        return encode(round,true);
    }

    public static String encodeFull(RoundFact round) { return encode(round,false); }

    private static String encode(RoundFact round, boolean compact) {
        GameRuleCore.validate(round);
        StringBuilder out = new StringBuilder(VERSION).append('|').append(modeCode(round.mode())).append('|');
        for (int i = 0; i < round.steps().size(); i++) {
            if (i != 0) out.append(';');
            RoundFact.Step step = round.steps().get(i);
            if (compact && markerEligible(round,i)) { out.append('#'); continue; }
            for (int symbol : step.ps()) out.append((char) ('0' + symbol));
            out.append('.');
            for (int mul : step.muls()) out.append(Character.forDigit(mul, 36));
            out.append('.').append(Integer.toString(step.wheelMultiplier(), 36));
        }
        return out.toString();
    }

    public static RoundFact decode(String text) {
        if (text == null || text.length() < 8 || text.length() > 180) throw new IllegalArgumentException("Invalid fact member length");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127 || c < 32) throw new IllegalArgumentException("Member must be printable ASCII");
        }
        String[] fields = text.split("\\|", -1);
        if (fields.length != 3 || !VERSION.equals(fields[0]) || fields[1].length() != 1) {
            throw new IllegalArgumentException("Unsupported fact member header");
        }
        RoundFact.Mode mode = switch (fields[1].charAt(0)) {
            case 'L' -> RoundFact.Mode.ORDINARY_LOSS;
            case 'B' -> RoundFact.Mode.ORDINARY_WIN;
            case 'W' -> RoundFact.Mode.LUCKY_WHEEL;
            case 'F' -> RoundFact.Mode.LUCKY_FEATURE;
            default -> throw new IllegalArgumentException("Unknown fact mode");
        };
        List<RoundFact.Step> steps = new ArrayList<>();
        String[] entries = fields[2].split(";", -1);
        int expected = mode == RoundFact.Mode.LUCKY_FEATURE ? 8 : 1;
        if (entries.length != expected) throw new IllegalArgumentException("Invalid step count");
        boolean[] markers = new boolean[entries.length];
        // Check the complete marker syntax before invoking the generator.
        for (int i=0;i<entries.length;i++) {
            if (!entries[i].contains("#")) continue;
            if (!entries[i].equals("#") || !(mode == RoundFact.Mode.ORDINARY_LOSS
                    || mode == RoundFact.Mode.LUCKY_FEATURE && i > 0)) {
                throw new IllegalArgumentException("Marker not allowed in this mode/position");
            }
            markers[i]=true;
        }
        for (String entry : entries) {
            if (entry.equals("#")) {
                steps.add(LossHolder.MODEL.independentLoss(mode == RoundFact.Mode.LUCKY_FEATURE));
                continue;
            }
            String[] parts = entry.split("\\.", -1);
            if (parts.length != 3 || parts[0].length() != 9 || parts[1].length() != 3 || parts[2].isEmpty()) {
                throw new IllegalArgumentException("Invalid step shape");
            }
            List<Integer> ps = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                char c = parts[0].charAt(i);
                if (c < '0' || c > '6') throw new IllegalArgumentException("Invalid symbol encoding");
                ps.add(c - '0');
            }
            List<Integer> muls = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                char c = parts[1].charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z'))) throw new IllegalArgumentException("Invalid multiplier encoding");
                muls.add(Character.digit(c, 36));
            }
            int wheel;
            try { wheel = Integer.parseInt(parts[2], 36); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid wheel encoding", e); }
            if (!Integer.toString(wheel, 36).equals(parts[2])) throw new IllegalArgumentException("Non-canonical wheel encoding");
            steps.add(new RoundFact.Step(ps, muls, wheel));
        }
        RoundFact round = new RoundFact(mode, steps);
        GameRuleCore.validate(round);
        String[] full = encodeFull(round).split("\\|", -1);
        String[] canonicalSteps = full[2].split(";", -1);
        for (int i=0;i<markers.length;i++) if(markers[i]) canonicalSteps[i]="#";
        String canonical = full[0]+"|"+full[1]+"|"+String.join(";",canonicalSteps);
        if (!canonical.equals(text)) throw new IllegalArgumentException("Non-canonical fact member");
        return round;
    }

    static boolean markerEligible(RoundFact round,int index) {
        boolean position = round.mode()==RoundFact.Mode.ORDINARY_LOSS
                || round.feature() && index>0;
        RoundFact.Step step=round.steps().get(index);
        return position && !step.wheel() && ResultUtil.evaluate(step,round.feature()).units()==0;
    }

    /** Verify semantic identity; only eligible zero steps may have newly sampled visible material. */
    public static void verifyEquivalent(RoundFact before,RoundFact after) {
        GameRuleCore.validate(before);GameRuleCore.validate(after);
        if(before.mode()!=after.mode() || before.steps().size()!=after.steps().size())
            throw new IllegalStateException("Codec changed round boundary");
        for(int i=0;i<before.steps().size();i++) {
            if(ResultUtil.evaluate(before.steps().get(i),before.feature()).units()
                    !=ResultUtil.evaluate(after.steps().get(i),after.feature()).units())
                throw new IllegalStateException("Codec changed step payout");
            if(!markerEligible(before,i) && !before.steps().get(i).equals(after.steps().get(i)))
                throw new IllegalStateException("Codec changed a retained step");
        }
    }

    private static final class LossHolder {
        private static final DealingModel MODEL = create();
        private static DealingModel create() {
            try { return new DealingModel(new java.util.Properties()); }
            catch(java.io.IOException e) { throw new IllegalStateException("Marker model unavailable",e); }
        }
    }

    private static char modeCode(RoundFact.Mode mode) {
        return switch (mode) {
            case ORDINARY_LOSS -> 'L';
            case ORDINARY_WIN -> 'B';
            case LUCKY_WHEEL -> 'W';
            case LUCKY_FEATURE -> 'F';
        };
    }
}
