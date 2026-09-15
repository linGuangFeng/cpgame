package com.cpgame.batcha.g32;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Silver/gold transform, explode, gravity, and refill. Extra top is independent of main 5. */
public final class Cascade {
    private Cascade() { }

    public static Board next(Board previous, GameRuleCore.BoardResult result, SecureRandom random,
                             EmpiricalColumnModel model) {
        Set<Integer> win = new HashSet<>();
        for (WinMatch match : result.matches()) {
            for (List<Integer> group : match.reelGroups()) win.addAll(group);
        }
        Set<Integer> silver = new HashSet<>(previous.silver());
        Set<Integer> gold = new HashSet<>(previous.gold());
        List<List<Survivor>> extras = new ArrayList<>();
        List<List<Survivor>> mains = new ArrayList<>();
        for (int c = 0; c < 6; c++) {
            extras.add(new ArrayList<>());
            mains.add(new ArrayList<>());
        }
        for (Token token : GameRuleCore.parse(previous.tokens())) {
            Survivor survivor = null;
            if (!win.contains(token.coord())) {
                survivor = new Survivor(token.symbol(), token.height(), gold.contains(token.coord()));
            } else if (silver.contains(token.coord())) {
                String symbol = GameRuleCore.TRANSFORM_SYMBOLS.get(random.nextInt(GameRuleCore.TRANSFORM_SYMBOLS.size()));
                survivor = new Survivor(symbol, token.height(), true);
            } else if (gold.contains(token.coord())) {
                survivor = new Survivor("Wild", token.height(), false);
            }
            if (survivor == null) continue;
            if (token.extra()) extras.get(token.reel()).add(survivor);
            else mains.get(token.reel()).add(survivor);
        }
        List<Token> assembled = new ArrayList<>();
        List<Integer> nextSilver = new ArrayList<>();
        List<Integer> nextGold = new ArrayList<>();
        for (int c = 0; c < 6; c++) {
            List<Survivor> extra = extras.get(c);
            List<Survivor> main = mains.get(c);
            if (c > 0 && c < 5 && extra.isEmpty()) {
                extra.add(read(model.drawFill("FILL", c, 1, random).getFirst()));
            }
            int used = main.stream().mapToInt(Survivor::height).sum();
            List<Survivor> filled = new ArrayList<>();
            int guard = 0;
            while (used < 5) {
                if (++guard > 24) throw new IllegalStateException("cannot fill reel " + c);
                int remain = 5 - used;
                Survivor candidate = read(model.drawFill("FILL", c, remain, random).getFirst());
                int height = candidate.height();
                if (c == 0 || c == 5) height = 1;
                if (height > remain) height = remain;
                filled.add(new Survivor(candidate.symbol(), height, false));
                used += height;
            }
            List<Survivor> reel = new ArrayList<>();
            if (c > 0 && c < 5) reel.addAll(extra);
            reel.addAll(filled);
            reel.addAll(main);
            int index = 0;
            int heightSum = 0;
            for (int i = 0; i < reel.size(); i++) {
                Survivor item = reel.get(i);
                boolean extraCell = c > 0 && c < 5 && i == 0;
                int coord = c * 10 + index;
                assembled.add(new Token(item.symbol(), item.height(), c, index, coord, extraCell));
                if (item.gold()) nextGold.add(coord);
                else if (item.height() >= 2 && !"Wild".equals(item.symbol()) && !"Scat".equals(item.symbol())
                    && c > 0 && c < 5 && random.nextInt(959) < 364) {
                    nextSilver.add(coord);
                }
                if (extraCell) {
                    index++;
                    heightSum = 0;
                } else {
                    index++;
                    heightSum += item.height();
                }
            }
            if (heightSum != 5) throw new IllegalStateException("main height of reel " + c + " is " + heightSum);
        }
        return new Board(GameRuleCore.toRskl(assembled), nextSilver, nextGold);
    }

    private static Survivor read(String wire) {
        return new Survivor(wire.substring(1), wire.charAt(0) - '0', false);
    }

    private record Survivor(String symbol, int height, boolean gold) { }

    public record Board(List<String> tokens, List<Integer> silver, List<Integer> gold) {
        public Board {
            tokens = List.copyOf(tokens);
            silver = List.copyOf(silver);
            gold = List.copyOf(gold);
        }
    }
}
