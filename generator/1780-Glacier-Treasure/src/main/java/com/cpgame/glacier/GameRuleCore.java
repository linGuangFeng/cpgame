package com.cpgame.glacier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Glacier Treasure 1780 deterministic rules. Sampling lives in BoardFactory. */
public final class GameRuleCore {
    public static final String RULES_HASH = "0139e446c93699f20cd06048145312e652edf09876efc7d4ff26cc207a73172e";
    public static final List<String> BEHAVIOR_IDS = List.of(
        "1780.board.geometry", "1780.payout.leftways", "1780.cascade.frames",
        "1780.multiplier.normal", "1780.multiplier.free", "1780.scatter.initial",
        "1780.free.terminal", "1780.buy.request");
    public static final int SCATTER = 12;
    public static final int WILD = 13;
    public static final int COLS = 6;
    public static final int HEIGHT = 5;
    public static final int HORIZONTALS = 4;
    public static final int BASE_LINES = 20;
    public static final int BUY_COST_MULTIPLE = 60;
    public static final int BIG_WIN_RATIO = 20;
    private static final int[][] ODDS = {{},
        {30,40,60,80},{20,25,50,70},{10,25,40,60},{8,15,20,30},
        {6,10,12,15},{6,10,12,15},{4,6,8,10},{4,6,8,10},
        {1,2,3,4},{1,2,3,4},{1,2,3,4}};

    public record Symbol(int id, int prop, int grid, int frame) {
        public Symbol {
            if (id <= 0 || prop < 1 || prop > 13 || grid < 1 || grid > 4 || frame < 0 || frame > 2)
                throw new IllegalArgumentException("Invalid symbol");
            if (frame != 0 && (prop >= 12 || grid < 2))
                throw new IllegalArgumentException("Frames require large ordinary symbol");
            if (prop == 12 && grid > 2) throw new IllegalArgumentException("Scatter height exceeds client bound");
        }
        public Symbol withId(int newId) { return new Symbol(newId, prop, grid, frame); }
        public Symbol asGold(int newProp) { return new Symbol(id, newProp, grid, 2); }
        public Symbol asWild() { return new Symbol(id, WILD, grid, 0); }
    }

    public record Board(List<List<Symbol>> columns, List<Symbol> horizontal) {
        public Board {
            columns = columns.stream().map(List::copyOf).toList();
            horizontal = List.copyOf(horizontal);
            if (columns.size() != 6 || horizontal.size() != 4) throw new IllegalArgumentException("Geometry");
            Set<Integer> ids = new HashSet<>();
            for (int c=0;c<6;c++) {
                int h=0;
                for (Symbol s:columns.get(c)) {
                    if (!ids.add(s.id())) throw new IllegalArgumentException("Duplicate symbol id");
                    if ((c==0 || c==5) && (s.grid()!=1 || s.prop()==13))
                        throw new IllegalArgumentException("Illegal edge symbol");
                    h+=s.grid();
                }
                if (h!=5) throw new IllegalArgumentException("Column height");
            }
            for (Symbol s:horizontal)
                if (s.grid()!=1 || s.frame()!=0 || !ids.add(s.id())) throw new IllegalArgumentException("Horizontal symbol");
        }
        public List<Symbol> reel(int c) {
            var out=new ArrayList<>(columns.get(c));
            if (c>0 && c<5) out.add(horizontal.get(c-1));
            return List.copyOf(out);
        }
        public List<Symbol> symbols() {
            var all=new ArrayList<Symbol>();
            columns.forEach(all::addAll); all.addAll(horizontal); return List.copyOf(all);
        }
        public int maxId() {
            return symbols().stream().mapToInt(Symbol::id).max().orElse(0);
        }
        public int scatterUnits() {
            return symbols().stream().filter(s->s.prop()==SCATTER).mapToInt(Symbol::grid).sum();
        }
        public int scatterUnitsOnReel(int c) {
            int n=0;
            for (Symbol s:columns.get(c)) if (s.prop()==SCATTER) n+=s.grid();
            if (c>0 && c<5 && horizontal.get(c-1).prop()==SCATTER) n+=horizontal.get(c-1).grid();
            return n;
        }
        public int wildCount() { return (int) symbols().stream().filter(s->s.prop()==WILD).count(); }
        public int wildOnReel(int c) {
            int n=0;
            for (Symbol s:columns.get(c)) if (s.prop()==WILD) n++;
            if (c>0 && c<5 && horizontal.get(c-1).prop()==WILD) n++;
            return n;
        }
    }

    public record Win(int prop, int reels, int ways, int odds, int multiplier, BigDecimal amount) {}
    public record Evaluation(List<Win> wins, Set<Integer> winningIds, BigDecimal total, int scatters) {
        public Evaluation { wins=List.copyOf(wins); winningIds=Set.copyOf(winningIds); }
        public boolean terminal() { return wins.isEmpty(); }
        public int unitProduct() {
            int sum=0;
            for (Win w:wins) sum=Math.addExact(sum, Math.multiplyExact(Math.multiplyExact(w.odds(), w.ways()), w.multiplier()));
            return sum;
        }
    }

    public int odds(int prop, int reels) {
        if (prop<1 || prop>11 || reels<3 || reels>6) return 0;
        return ODDS[prop][reels-3];
    }

    public Evaluation evaluate(Board board, BigDecimal stakeUnit, int multiplier) {
        if (stakeUnit.signum()<=0 || multiplier<1) throw new IllegalArgumentException("Stake or multiplier");
        var wins=new ArrayList<Win>(); var winning=new HashSet<Integer>(); var total=BigDecimal.ZERO;
        for (int prop=1;prop<=11;prop++) {
            int reels=0, ways=1; var matched=new ArrayList<Integer>();
            for (int c=0;c<6;c++) {
                int count=0;
                for (Symbol s:board.reel(c)) if (s.prop()==prop || s.prop()==13) { count++; matched.add(s.id()); }
                if (count==0) break;
                ways=Math.multiplyExact(ways,count); reels++;
            }
            int odd=odds(prop,reels);
            if (odd==0) continue;
            var amount=stakeUnit.multiply(BigDecimal.valueOf(odd)).multiply(BigDecimal.valueOf(ways))
                    .multiply(BigDecimal.valueOf(multiplier));
            wins.add(new Win(prop,reels,ways,odd,multiplier,amount));winning.addAll(matched);total=total.add(amount);
        }
        return new Evaluation(wins,winning,total,board.scatterUnits());
    }

    public BigDecimal stakeUnit(BigDecimal betSize, int level) {
        if (betSize.signum()<=0 || level<1 || level>10) throw new IllegalArgumentException("Bet");
        return betSize.multiply(BigDecimal.valueOf(level));
    }
    public BigDecimal betAmount(BigDecimal betSize, int level) { return stakeUnit(betSize,level).multiply(BigDecimal.valueOf(BASE_LINES)); }
    public BigDecimal buyCost(BigDecimal betSize, int level) { return betAmount(betSize,level).multiply(BigDecimal.valueOf(BUY_COST_MULTIPLE)); }

    /** Award uses Scatter *symbol count*, not grid-sum units. nums reports units. */
    public int scatterSymbolCount(Board board) {
        return (int) board.symbols().stream().filter(s->s.prop()==SCATTER).count();
    }
    public int initialFreeAward(int scatterSymbols) {
        if (scatterSymbols<0) throw new IllegalArgumentException("Scatter count");
        return scatterSymbols<4 ? 0 : Math.addExact(10, Math.multiplyExact(scatterSymbols-4,2));
    }
    public int nextMultiplier(int current, boolean free, boolean won) {
        if (current<1) throw new IllegalArgumentException("Multiplier");
        return won ? Math.addExact(current,free?2:1) : current;
    }

    public int integerRatio(int unitProductSum) {
        if (unitProductSum<0) throw new IllegalArgumentException("ratio");
        return unitProductSum;
    }

    public int bigWinFlag(BigDecimal totalWin, BigDecimal betGold) {
        if (betGold.signum()<=0 || totalWin.signum()<=0) return 0;
        return totalWin.divide(betGold, 8, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(BIG_WIN_RATIO))>=0 ? 1 : 0;
    }

    public List<Map<String,Object>> winArray(Evaluation evaluation) {
        var out=new ArrayList<Map<String,Object>>();
        for (Win w:evaluation.wins()) {
            var m=new LinkedHashMap<String,Object>();
            m.put("multiple", w.multiplier());
            m.put("odd", w.odds());
            m.put("prop", w.prop());
            m.put("reel", w.reels()-1);
            m.put("way", w.ways());
            m.put("win_amout", w.amount());
            out.add(m);
        }
        return out;
    }

    public Map<String,Object> symbolJson(Symbol s, boolean horizontal, boolean win) {
        var m=new LinkedHashMap<String,Object>();
        m.put("grid", s.grid());
        m.put("id", s.id());
        m.put("is_special", s.frame());
        if (horizontal) m.put("is_top", 1);
        m.put("is_win", win ? 1 : 0);
        m.put("prop", s.prop());
        return m;
    }

    public Map<String,Object> stepJson(Board board, Evaluation evaluation) {
        var m=new LinkedHashMap<String,Object>();
        var cols=new ArrayList<Object>();
        for (var col:board.columns()) {
            var cells=new ArrayList<Object>();
            for (Symbol s:col) cells.add(symbolJson(s, false, evaluation.winningIds().contains(s.id())));
            cols.add(cells);
        }
        var hs=new ArrayList<Object>();
        for (Symbol s:board.horizontal()) hs.add(symbolJson(s, true, evaluation.winningIds().contains(s.id())));
        m.put("horizontals", hs);
        m.put("props_value", cols);
        m.put("total_amout", evaluation.total());
        m.put("win_array", winArray(evaluation));
        return m;
    }

    public Board renumber(Board board, int startId) {
        int id=startId;
        var cols=new ArrayList<List<Symbol>>();
        for (var col:board.columns()) {
            var next=new ArrayList<Symbol>();
            for (Symbol s:col) next.add(s.withId(id++));
            cols.add(next);
        }
        var hs=new ArrayList<Symbol>();
        for (Symbol s:board.horizontal()) hs.add(s.withId(id++));
        return new Board(cols, hs);
    }

    public Board continueIds(Board previous, Board packedSurvivorsAndNew, Set<Integer> winning) {
        Map<Integer,Symbol> old=new LinkedHashMap<>();
        for (Symbol s:previous.symbols()) old.put(s.id(), s);
        int nextId=previous.maxId()+1;
        var cols=new ArrayList<List<Symbol>>();
        for (int c=0;c<6;c++) {
            List<Symbol> prevCol=previous.columns().get(c);
            List<Integer> survivorIds=new ArrayList<>();
            for (Symbol s:prevCol) {
                if (!winning.contains(s.id())) survivorIds.add(s.id());
                else if (s.frame()==1 || s.frame()==2) survivorIds.add(s.id());
            }
            var next=new ArrayList<Symbol>();
            int si=0;
            for (Symbol s:packedSurvivorsAndNew.columns().get(c)) {
                if (si<survivorIds.size()) next.add(s.withId(survivorIds.get(si++)));
                else next.add(s.withId(nextId++));
            }
            if (si!=survivorIds.size()) throw new IllegalStateException("survivor count mismatch col "+c);
            cols.add(next);
        }
        List<Integer> survH=new ArrayList<>();
        for (Symbol s:previous.horizontal()) {
            if (!winning.contains(s.id())) survH.add(s.id());
            else if (s.frame()==1 || s.frame()==2) survH.add(s.id());
        }
        var hs=new ArrayList<Symbol>();
        int si=0;
        for (Symbol s:packedSurvivorsAndNew.horizontal()) {
            if (si<survH.size()) hs.add(s.withId(survH.get(si++)));
            else hs.add(s.withId(nextId++));
        }
        if (si!=survH.size()) throw new IllegalStateException("survivor count mismatch horizontal");
        return new Board(cols, hs);
    }

    public List<Symbol> survivors(List<Symbol> axis, Set<Integer> winning, Map<Integer,Symbol> transformed) {
        var out=new ArrayList<Symbol>();
        for (Symbol s:axis) {
            if (transformed.containsKey(s.id())) out.add(transformed.get(s.id()));
            else if (!winning.contains(s.id())) out.add(s);
        }
        return out;
    }
}
