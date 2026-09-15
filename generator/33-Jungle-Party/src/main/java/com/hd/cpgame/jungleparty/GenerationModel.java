package com.hd.cpgame.jungleparty;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provider-trained hierarchical joint model for complete raw-gid-33 Rounds. */
final class GenerationModel {
    static final String MODEL_HASH = "8522e03f81318ebb80df6219fcb41d380314f12911062c0888066a9464067638";
    private static final Model MODEL = load();

    private GenerationModel() {}

    static Plan sample(SecureRandom random, GameRuleCore.Scenario requested) {
        if(requested==GameRuleCore.Scenario.ORDINARY_LOSS)
            return LossDefaults.POOL.generate(()->lossCandidate(random),random::nextInt);
        return sampleCandidate(random,requested);
    }
    static Plan lossCandidate(SecureRandom random){return sampleCandidate(random,GameRuleCore.Scenario.ORDINARY_LOSS);}
    private static final class LossDefaults {
        private static final SecureRandom RANDOM=new SecureRandom();
        static final ZeroLossSupport<Plan> POOL=new ZeroLossSupport<>(()->lossCandidate(RANDOM),p->
            p.boards().size()==1&&java.util.Arrays.stream(p.boards().get(0).cells()).filter(v->v==GameRuleCore.Symbol.Scat).count()<3
            &&GameRuleCore.evaluate(p.boards().get(0),1,new java.math.BigDecimal("0.02"),0).award().signum()==0,p->p);
    }
    private static Plan sampleCandidate(SecureRandom random, GameRuleCore.Scenario requested) {
        String wanted = switch (requested) {
            case ORDINARY_LOSS -> "LOSS";
            case ORDINARY_WIN -> "WIN";
            case SCATTER_FREE_ROUNDS -> "FREE";
            case RANDOM -> null;
        };
        List<Macro> macroPool = wanted == null ? MODEL.macros : MODEL.macrosByOutcome.get(wanted);
        if (macroPool == null || macroPool.isEmpty()) throw new IllegalStateException("generation model has no macro pool for " + wanted);
        Macro macro = macroPool.get(random.nextInt(macroPool.size()));
        GameRuleCore.Scenario scenario = switch (macro.outcome) {
            case "LOSS" -> GameRuleCore.Scenario.ORDINARY_LOSS;
            case "WIN" -> GameRuleCore.Scenario.ORDINARY_WIN;
            case "FREE" -> GameRuleCore.Scenario.SCATTER_FREE_ROUNDS;
            default -> throw new IllegalStateException("unknown outcome " + macro.outcome);
        };
        List<GameRuleCore.Board> boards = new ArrayList<>();
        boards.add(sampleBoard(random, MODEL.initialByOutcome.get(macro.outcome)));
        if (scenario == GameRuleCore.Scenario.SCATTER_FREE_ROUNDS) {
            int currentFsn = macro.initialFsn;
            int retriggersLeft = macro.retriggers;
            for (int nfsc = 1; nfsc <= macro.finalFsn; nfsc++) {
                int slotsLeftInWindow = currentFsn - nfsc + 1;
                boolean retrigger = retriggersLeft > 0 &&
                    (slotsLeftInWindow <= retriggersLeft || random.nextInt(slotsLeftInWindow) < retriggersLeft);
                boards.add(sampleBoard(random, retrigger ? MODEL.freeRetrigger : MODEL.freeNormal));
                if (retrigger) { currentFsn += 8; retriggersLeft--; }
            }
            if (currentFsn != macro.finalFsn || retriggersLeft != 0)
                throw new IllegalStateException("inconsistent free-round macro " + macro);
        }
        return new Plan(scenario, macro.initialFsn, macro.finalFsn, macro.rpx, List.copyOf(boards));
    }

    private static GameRuleCore.Board sampleBoard(SecureRandom random, List<GameRuleCore.Symbol[]> pool) {
        if (pool == null || pool.isEmpty()) throw new IllegalStateException("empty complete-state kernel");
        GameRuleCore.Symbol[] source = pool.get(random.nextInt(pool.size()));
        Map<GameRuleCore.Symbol,GameRuleCore.Symbol> mapping = new EnumMap<>(GameRuleCore.Symbol.class);
        permuteGroup(random, mapping, GameRuleCore.Symbol.N9, GameRuleCore.Symbol.J, GameRuleCore.Symbol.Q, GameRuleCore.Symbol.T);
        permuteGroup(random, mapping, GameRuleCore.Symbol.A, GameRuleCore.Symbol.K);
        permuteGroup(random, mapping, GameRuleCore.Symbol.H4, GameRuleCore.Symbol.H5);
        GameRuleCore.Symbol[] cells = new GameRuleCore.Symbol[source.length];
        boolean verticalFlip = random.nextBoolean();
        for (int reel=0; reel<GameRuleCore.REELS; reel++) for (int row=0; row<GameRuleCore.ROWS; row++) {
            int sourceRow = verticalFlip ? GameRuleCore.ROWS - 1 - row : row;
            GameRuleCore.Symbol symbol = source[reel * GameRuleCore.ROWS + sourceRow];
            cells[reel * GameRuleCore.ROWS + row] = mapping.getOrDefault(symbol, symbol);
        }
        return new GameRuleCore.Board(cells);
    }

    private static void permuteGroup(SecureRandom random, Map<GameRuleCore.Symbol,GameRuleCore.Symbol> map,
                                     GameRuleCore.Symbol... group) {
        List<GameRuleCore.Symbol> shuffled = new ArrayList<>(List.of(group));
        Collections.shuffle(shuffled, random);
        for (int i=0;i<group.length;i++) map.put(group[i], shuffled.get(i));
    }

    private static Model load() {
        List<Macro> macros = new ArrayList<>();
        Map<String,List<Macro>> macrosByOutcome = new HashMap<>();
        Map<String,List<GameRuleCore.Symbol[]>> initial = new HashMap<>();
        List<GameRuleCore.Symbol[]> freeNormal = new ArrayList<>(), freeRetrigger = new ArrayList<>();
        try (InputStream input = GenerationModel.class.getResourceAsStream("/generation-model-v37.tsv")) {
            if (input == null) throw new IllegalStateException("generation-model-v37.tsv is missing");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line=reader.readLine()) != null;) {
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("SPLIT\t") || line.startsWith("HOLDOUT\t")) continue;
                    String[] p=line.split("\t");
                    if ("M".equals(p[0])) {
                        Macro m=new Macro(Integer.parseInt(p[1]),p[2],Integer.parseInt(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]));
                        macros.add(m);macrosByOutcome.computeIfAbsent(m.outcome,k->new ArrayList<>()).add(m);
                    } else if ("I".equals(p[0])) initial.computeIfAbsent(p[1],k->new ArrayList<>()).add(parseBoard(p[2]));
                    else if ("F".equals(p[0])) ("RETRIGGER".equals(p[1])?freeRetrigger:freeNormal).add(parseBoard(p[2]));
                }
            }
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
        if (macros.size()!=1375 || initial.values().stream().mapToInt(List::size).sum()!=1375 || freeNormal.size()!=562 || freeRetrigger.size()!=10)
            throw new IllegalStateException("generation model cardinality mismatch");
        return new Model(List.copyOf(macros),freeze(macrosByOutcome),freezeBoards(initial),List.copyOf(freeNormal),List.copyOf(freeRetrigger));
    }

    private static GameRuleCore.Symbol[] parseBoard(String csv) {
        String[] names=csv.split(","); if(names.length!=15)throw new IllegalArgumentException("model board is not 15 cells");
        GameRuleCore.Symbol[] board=new GameRuleCore.Symbol[15];for(int i=0;i<15;i++)board[i]=GameRuleCore.parseSymbol(names[i]);return board;
    }
    private static <T> Map<String,List<T>> freeze(Map<String,List<T>> source) {Map<String,List<T>> out=new HashMap<>();source.forEach((k,v)->out.put(k,List.copyOf(v)));return Map.copyOf(out);}
    private static Map<String,List<GameRuleCore.Symbol[]>> freezeBoards(Map<String,List<GameRuleCore.Symbol[]>> source) {return freeze(source);}

    record Macro(int ordinal,String outcome,int initialFsn,int finalFsn,int rpx,int retriggers) {}
    record Plan(GameRuleCore.Scenario scenario,int initialFsn,int finalFsn,int rpx,List<GameRuleCore.Board> boards) {}
    record Model(List<Macro> macros,Map<String,List<Macro>> macrosByOutcome,Map<String,List<GameRuleCore.Symbol[]>> initialByOutcome,List<GameRuleCore.Symbol[]> freeNormal,List<GameRuleCore.Symbol[]> freeRetrigger) {}
}
