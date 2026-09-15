package com.cpgame.glacier;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.cpgame.glacier.GameRuleCore.*;

/** Offline regression only. Raw fixture expectations never enter a production runtime. */
public final class HistoryRegression {
    private final GameRuleCore core=new GameRuleCore();
    private final ResultUtil oracle=new ResultUtil();
    private final List<String> failures=new ArrayList<>();
    private final Map<String,Integer> counts=new TreeMap<>();
    private final Map<String,Map<String,Integer>> entryCounts=new TreeMap<>();
    private final Map<String,Integer> entryEvents=new TreeMap<>();
    private int steps,deliveries,winRows,cascades,negativeChecks,maxScatter,maxWild,booleanBetFields;
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object o){return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") static List<Object> arr(Object o){return (List<Object>)o;}
    static BigDecimal dec(Object o){return (BigDecimal)o;}
    static int num(Object o){return dec(o).intValueExact();}
    static Map<String,Object> body(Object o){var v=obj(o);return obj(v.getOrDefault("body",v));}
    static Map<String,Object> read(Path p)throws Exception{return obj(Json.parse(Files.readString(p)));}
    static String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static Symbol symbol(Object o){var x=obj(o);return new Symbol(num(x.get("id")),num(x.get("prop")),num(x.get("grid")),num(x.get("is_special")));}
    static Board board(Map<String,Object> s){
        var cols=new ArrayList<List<Symbol>>();for(Object c:arr(s.get("props_value")))cols.add(arr(c).stream().map(HistoryRegression::symbol).toList());
        return new Board(cols,arr(s.get("horizontals")).stream().map(HistoryRegression::symbol).toList());
    }
    private void check(boolean v,String label){if(!v)failures.add(label);}
    private void equal(BigDecimal a,Object b,String label){check(a.compareTo(dec(b))==0,label+" expected="+b+" actual="+a);}
    private void observe(String entry,Board current,Board previous){
        entryEvents.merge(entry,1,Integer::sum);
        var old=new HashMap<Integer,Symbol>();if(previous!=null)previous.symbols().forEach(s->old.put(s.id(),s));
        for(int axis=0;axis<7;axis++)for(Symbol s:axis==6?current.horizontal():current.columns().get(axis)){
            Symbol before=old.get(s.id());
            if(before!=null && before.equals(s))continue;
            String key=entry;
            if(before!=null)key=entry+"."+ (before.frame()==1?"silverToGold":"goldToWild");
            entryCounts.computeIfAbsent(key,k->new TreeMap<>()).merge(axis+":"+s.prop()+":"+s.grid()+":"+s.frame(),1,Integer::sum);
        }
        maxScatter=Math.max(maxScatter,current.symbols().stream().filter(s->s.prop()==12).mapToInt(Symbol::grid).sum());
        maxWild=Math.max(maxWild,(int)current.symbols().stream().filter(s->s.prop()==13).count());
    }
    private void negativeChecks(Board source,BigDecimal unit,Map<Integer,Map<Integer,Integer>> table){
        for(String kind:List.of("edgeWild","duplicateId","singleFrame","largeEdge","scatterFrame")){
            try{
                var cols=new ArrayList<List<Symbol>>();source.columns().forEach(c->cols.add(new ArrayList<>(c)));
                Symbol old=cols.get(0).get(0);Symbol replacement=switch(kind){
                    case "edgeWild" -> new Symbol(old.id(),13,1,0);
                    case "duplicateId" -> new Symbol(cols.get(0).get(1).id(),old.prop(),1,0);
                    case "singleFrame" -> new Symbol(old.id(),old.prop(),1,1);
                    case "largeEdge" -> new Symbol(old.id(),old.prop(),2,0);
                    default -> new Symbol(old.id(),12,2,1);
                };
                cols.get(0).set(0,replacement);new Board(cols,source.horizontal());failures.add("Negative accepted: "+kind);
            }catch(IllegalArgumentException expected){negativeChecks++;}
        }
        var correct=core.evaluate(source,unit,1);
        var altered=new Evaluation(correct.wins(),correct.winningIds(),correct.total().add(BigDecimal.ONE),correct.scatters());
        try{oracle.verify(altered,oracle.evaluate(source,unit,1,table));failures.add("Payout corruption not rejected");}
        catch(IllegalStateException expected){negativeChecks++;}
        for(String malformed:List.of("{\"a\":1,\"a\":2}","[01]","[1,]")){
            try{Json.parse(malformed);failures.add("Invalid evidence JSON accepted");}
            catch(IllegalArgumentException expected){negativeChecks++;}
        }
    }
    private Map<String,Object> run(Path base)throws Exception{
        Path fixture=base.resolve("fixtures/1780-Glacier-Treasure");
        Path historyPath=fixture.resolve("history-view/response.json");
        Path initPath=fixture.resolve("enter/initRoom/response.json");
        Path rulesPath=base.resolve("protocol/1780-Glacier-Treasure/rules.json");
        check(hash(rulesPath).equals(GameRuleCore.RULES_HASH),"rulesHash mismatch");
        var init=obj(body(read(initPath)).get("data"));
        var table=new TreeMap<Integer,Map<Integer,Integer>>();
        for(Object value:arr(init.get("prop_odds"))){
            var t=obj(value);var odds=new TreeMap<Integer,Integer>();
            for(Object item:arr(t.get("odds"))){var x=obj(item);odds.put(num(x.get("num")),num(x.get("odds")));}
            table.put(num(t.get("prop_id")),odds);
        }
        var rows=arr(obj(body(read(historyPath)).get("data")).get("list"));
        Set<Object> roundIds=new HashSet<>();int round=0;
        for(Object row:rows){
            var r=obj(row);var ds=arr(r.get("results"));check(roundIds.add(r.get("oid")),"Duplicate Round "+round);
            boolean feature=ds.size()>1 || num(obj(r.get("frees")).get("total_times"))>0;
            counts.merge(feature?"free_feature":dec(r.get("total_win")).signum()>0?"ordinary_win":"ordinary_loss",1,Integer::sum);
            int freeMultiple=2;BigDecimal freeWin=BigDecimal.ZERO;
            for(int di=0;di<ds.size();di++){
                var d=obj(ds.get(di));var rawSteps=arr(d.get("result"));var f=obj(d.get("frees"));
                boolean free=di>0;int mult=free?freeMultiple:1;
                // Nested History bet is boolean false. d.bet_gold is the base bet, not a free-step debit.
                if(d.get("bet") instanceof Boolean)booleanBetFields++;
                var unit=dec(d.get("bet_gold")).divide(BigDecimal.valueOf(20));
                int level=num(d.get("level"));var betSize=unit.divide(BigDecimal.valueOf(level));
                equal(core.betAmount(betSize,level),d.get("bet_gold"),"Bet formula");
                BigDecimal total=BigDecimal.ZERO;Board prev=null;Set<Integer> prevWins=Set.of();
                for(int si=0;si<rawSteps.size();si++){
                    var raw=obj(rawSteps.get(si));String at=round+"/"+di+"/"+si;Board b;
                    try{b=board(raw);}catch(RuntimeException ex){failures.add("Board "+at+": "+ex.getMessage());continue;}
                    var actual=core.evaluate(b,unit,mult);var independent=oracle.evaluate(b,unit,mult,table);
                    try{oracle.verify(actual,independent);}catch(RuntimeException ex){failures.add(at+": "+ex.getMessage());}
                    equal(actual.total(),raw.get("total_amout"),"Captured step "+at);
                    Set<Integer> flags=new HashSet<>();
                    for(Object c:arr(raw.get("props_value")))for(Object sv:arr(c)){var x=obj(sv);if(num(x.get("is_win"))==1)flags.add(num(x.get("id")));}
                    for(Object sv:arr(raw.get("horizontals"))){var x=obj(sv);if(num(x.get("is_win"))==1)flags.add(num(x.get("id")));}
                    check(actual.winningIds().equals(flags),"Winning flags "+at);
                    var expected=new TreeMap<Integer,Map<String,Object>>();
                    for(Object w:arr(raw.get("win_array"))){var m=obj(w);expected.put(num(m.get("prop")),m);winRows++;}
                    check(expected.size()==actual.wins().size(),"Win array size "+at);
                    for(Win w:actual.wins()){
                        var e=expected.get(w.prop());if(e==null){failures.add("Missing expected symbol "+at);continue;}
                        check(w.ways()==num(e.get("way")) && w.reels()-1==num(e.get("reel")) &&
                            w.odds()==num(e.get("odd")) && w.multiplier()==num(e.get("multiple")),"Win fields "+at);
                        equal(w.amount(),e.get("win_amout"),"Symbol payout "+at);
                    }
                    check(actual.terminal()==(si==rawSteps.size()-1),"Delivery terminal "+at);
                    if(prev!=null){cascades++;for(String e:oracle.verifyCascade(prev,b,prevWins))failures.add(at+": "+e);}
                    observe((free?"free":"main")+(si==0?".initial":".refill"),b,prev);
                    if(round==0 && di==0 && si==0)negativeChecks(b,unit,table);
                    prev=b;prevWins=actual.winningIds();total=total.add(actual.total());steps++;
                    mult=core.nextMultiplier(mult,free,!actual.terminal());
                    if(si==rawSteps.size()-1){
                        var nf=obj(d.get("new_free"));check(actual.scatters()==num(nf.get("nums")),"Scatter count "+at);
                        if(!free)check(core.initialFreeAward(core.scatterSymbolCount(b))==num(nf.get("new_times")),"Initial free award "+at);
                    }
                }
                equal(total,d.get("total_win"),"Delivery sum "+round+"/"+di);
                equal(dec(d.get("end_gold")).subtract(dec(d.get("start_gold"))),d.get("change_gold"),"Balance delta "+round+"/"+di);
                equal(total.subtract(free?BigDecimal.ZERO:dec(d.get("bet_gold"))),d.get("change_gold"),"Paid/free debit "+round+"/"+di);
                if(free)equal(dec(obj(ds.get(di-1)).get("end_gold")),d.get("start_gold"),"Delivery balance continuity "+round+"/"+di);
                deliveries++;
                if(free){
                    freeMultiple=mult;freeWin=freeWin.add(total);
                    check(mult==num(f.get("multiple")),"Free persistent multiplier "+round+"/"+di);
                    equal(freeWin,f.get("total_win_amount"),"Free accumulated win "+round+"/"+di);
                    var previous=obj(obj(ds.get(di-1)).get("frees"));
                    int awarded=num(obj(d.get("new_free")).get("new_times"));
                    check(num(f.get("surplus_times"))==num(previous.get("surplus_times"))-1+awarded,"Free countdown "+round+"/"+di);
                }
            }
            check(num(obj(obj(ds.get(ds.size()-1)).get("frees")).get("surplus_times"))==0,"Complete free Round "+round);
            round++;
        }
        var report=new LinkedHashMap<String,Object>();
        report.put("gameId",1780);report.put("generatedAt",Instant.now().toString());report.put("rulesHash",GameRuleCore.RULES_HASH);
        report.put("behaviorIds",GameRuleCore.BEHAVIOR_IDS);report.put("status",failures.isEmpty()?"PASS_ACCESSIBLE_HISTORY_SUBSET":"FAIL");
        report.put("readyForAcceptance",false);report.put("rawHistorySha256",hash(historyPath));report.put("rawInitSha256",hash(initPath));
        report.put("realHistoryRounds",rows.size());report.put("counts",counts);report.put("deliveries",deliveries);report.put("steps",steps);
        report.put("capturedWinBreakdowns",winRows);report.put("cascadesChecked",cascades);report.put("balanceDeliveriesChecked",deliveries);report.put("negativeChecksPassed",negativeChecks);
        report.put("historyBooleanBetFields",booleanBetFields);report.put("failures",failures);
        report.put("expectedSource","Raw unmodified Init paytable and History amounts/flags/win_array; independent path enumeration");
        report.put("holdoutOriginalRounds",0);report.put("generatedRounds",0);
        report.put("sampleStatus","SAMPLE_INSUFFICIENT");report.put("productionGenerator","NOT_IMPLEMENTED");
        report.put("scope","30 History rounds used for development regression; not a held-out 100-round spin corpus");
        var model=new LinkedHashMap<String,Object>();model.put("entryEvents",entryEvents);
        var dist=new TreeMap<String,Object>();
        entryCounts.forEach((entry,freq)->{
            int denominator=freq.values().stream().mapToInt(Integer::intValue).sum();
            var bins=new ArrayList<Object>();
            freq.forEach((key,n)->bins.add(Map.of("axis_prop_grid_frame",key,"count",n,"percent",BigDecimal.valueOf(n*100L).divide(BigDecimal.valueOf(denominator),8,java.math.RoundingMode.HALF_UP))));
            dist.put(entry,Map.of("denominatorNewOrTransformedSymbols",denominator,"bins",bins));
        });
        model.put("distributions",dist);model.put("observedMaxScatterUnitsPerBoard",maxScatter);model.put("observedMaxWildPerBoard",maxWild);
        model.put("scope","Descriptive only. Unchanged retained symbols excluded from refill counts; transformed symbols in separate bins. Not a sampling model.");
        model.put("source",historyPath.toString());model.put("generationCaps","UNRESOLVED_REQUIRES_FULL_CORPUS");report.put("observedEntryModel",model);
        return report;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Usage: HistoryRegression <cpgame-root> <output-json>");
        var test=new HistoryRegression();var report=test.run(Path.of(args[0]));
        Path out=Path.of(args[1]);Files.createDirectories(out.getParent());Files.writeString(out,Json.stringify(report)+"\n",StandardCharsets.UTF_8);
        System.out.println(Json.stringify(Map.of("status",report.get("status"),"rounds",report.get("realHistoryRounds"),
            "steps",report.get("steps"),"failures",test.failures,"report",out.toString())));
        if(!test.failures.isEmpty())System.exit(1);
    }
}
