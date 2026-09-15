package com.cpgame.glacier;
import java.math.BigDecimal;
import java.util.*;
import static com.cpgame.glacier.GameRuleCore.*;

/** Independent verifier: enumerates paths; never calls GameRuleCore.evaluate or its odds table. */
public final class ResultUtil {
    public record Oracle(Map<Integer,Integer> ways, Map<Integer,Integer> reels, Set<Integer> ids, BigDecimal total) {}
    public Oracle evaluate(Board board, BigDecimal unit, int multiplier, Map<Integer,Map<Integer,Integer>> evidencePaytable) {
        var ways=new TreeMap<Integer,Integer>();var reels=new TreeMap<Integer,Integer>();var ids=new HashSet<Integer>();
        var total=BigDecimal.ZERO;
        Set<Integer> candidates=new TreeSet<>();
        for (Symbol s:board.columns().get(0)) if(s.prop()<=11) candidates.add(s.prop());
        for (int prop:candidates) {
            var matches=new ArrayList<List<Symbol>>();
            for(int c=0;c<6;c++) {
                var row=new ArrayList<Symbol>();
                for(Symbol s:board.columns().get(c)) if(s.prop()==prop || s.prop()==13) row.add(s);
                if(c>=1 && c<=4) {
                    Symbol top=board.horizontal().get(c-1);
                    if(top.prop()==prop || top.prop()==13) row.add(top);
                }
                if(row.isEmpty()) break;
                matches.add(row);
            }
            if(matches.size()<3) continue;
            int count=countPaths(matches,0);
            Integer odd=evidencePaytable.getOrDefault(prop,Map.of()).get(matches.size());
            if(odd==null) throw new IllegalArgumentException("Evidence paytable lacks entry");
            ways.put(prop,count);reels.put(prop,matches.size());matches.forEach(xs->xs.forEach(s->ids.add(s.id())));
            total=total.add(unit.multiply(BigDecimal.valueOf(multiplier)).multiply(BigDecimal.valueOf(odd))
                .multiply(BigDecimal.valueOf(count)));
        }
        return new Oracle(Map.copyOf(ways),Map.copyOf(reels),Set.copyOf(ids),total);
    }
    private int countPaths(List<List<Symbol>> rows,int c) {
        if(c==rows.size()) return 1;
        int n=0;for(Symbol ignored:rows.get(c)) n=Math.addExact(n,countPaths(rows,c+1));return n;
    }
    public List<String> verifyCascade(Board before, Board after, Set<Integer> winning) {
        var errors=new ArrayList<String>();var next=new HashMap<Integer,Symbol>();var locations=new HashMap<Integer,Integer>();
        for(int c=0;c<6;c++) for(Symbol s:after.columns().get(c)){next.put(s.id(),s);locations.put(s.id(),c);}
        for(Symbol s:after.horizontal()){next.put(s.id(),s);locations.put(s.id(),6);}
        for(int c=0;c<7;c++) {
            var previous=c==6?before.horizontal():before.columns().get(c);
            var surviving=new ArrayList<Integer>();
            for(Symbol old:previous) {
                Symbol n=next.get(old.id());
                if(!winning.contains(old.id())) {
                    if(!old.equals(n)) errors.add("Nonwinning symbol changed: "+old.id());
                } else if(old.frame()==0) {
                    if(n!=null) errors.add("Winning ordinary symbol retained: "+old.id());
                } else if(old.frame()==1) {
                    if(n==null || n.frame()!=2 || n.prop()>11 || n.grid()!=old.grid())
                        errors.add("Silver transition: "+old.id());
                } else if(n==null || n.prop()!=13 || n.frame()!=0 || n.grid()!=old.grid())
                    errors.add("Gold transition: "+old.id());
                if(n!=null) {
                    if(!Objects.equals(locations.get(old.id()),c)) errors.add("Symbol changed axis: "+old.id());
                    surviving.add(old.id());
                }
            }
            var actual=new ArrayList<Integer>();var ss=new HashSet<>(surviving);
            for(Symbol n:c==6?after.horizontal():after.columns().get(c)) if(ss.contains(n.id())) actual.add(n.id());
            if(!actual.equals(surviving)) errors.add("Survivor ordering differs on axis "+c);
        }
        return List.copyOf(errors);
    }
    public void verify(Evaluation actual, Oracle oracle) {
        if(actual.total().compareTo(oracle.total())!=0 || !actual.winningIds().equals(oracle.ids()))
            throw new IllegalStateException("Core/oracle payout or symbols differ");
        var ways=new TreeMap<Integer,Integer>();var reels=new TreeMap<Integer,Integer>();
        for(Win w:actual.wins()){ways.put(w.prop(),w.ways());reels.put(w.prop(),w.reels());}
        if(!ways.equals(oracle.ways()) || !reels.equals(oracle.reels()))
            throw new IllegalStateException("Core/oracle ways differ");
    }
    public static Map<Integer,Map<Integer,Integer>> paytable() {
        var table=new TreeMap<Integer,Map<Integer,Integer>>();
        int[][] odds={{},{30,40,60,80},{20,25,50,70},{10,25,40,60},{8,15,20,30},
            {6,10,12,15},{6,10,12,15},{4,6,8,10},{4,6,8,10},{1,2,3,4},{1,2,3,4},{1,2,3,4}};
        for (int prop=1;prop<=11;prop++) {
            var m=new TreeMap<Integer,Integer>();
            for (int r=3;r<=6;r++) m.put(r, odds[prop][r-3]);
            table.put(prop, m);
        }
        return table;
    }
}
