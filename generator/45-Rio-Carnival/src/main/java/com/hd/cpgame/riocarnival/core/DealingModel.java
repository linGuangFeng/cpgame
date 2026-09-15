package com.hd.cpgame.riocarnival.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;

/** Aggregated training statistics: joint special-count shape, then conditional three-cell reel windows. */
public final class DealingModel {
    private static final JsonNode DATA = load();
    public static final int MAX_STEPS = DATA.get("maximumSteps").asInt();
    public static final int MAX_RETRIGGERS = DATA.get("maximumRetriggers").asInt();
    private DealingModel() {}
    private static JsonNode load() {
        try (InputStream in = DealingModel.class.getResourceAsStream("/rio-dealing-model.json")) {
            if (in == null) throw new IllegalStateException("Missing compiled dealing model");
            JsonNode d = new ObjectMapper().readTree(in);
            if (!GameRules.RULES_HASH.equals(d.get("rulesHash").asText())) throw new IllegalStateException("Model rulesHash mismatch");
            return d;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static String choose(JsonNode counts, RandomSource random) {
        int total = 0;
        for (JsonNode n : counts) total = Math.addExact(total, n.asInt());
        int draw = random.nextInt(total);
        Iterator<Map.Entry<String,JsonNode>> it = counts.fields();
        while (it.hasNext()) {
            Map.Entry<String,JsonNode> e = it.next();
            draw -= e.getValue().asInt();
            if (draw < 0) return e.getKey();
        }
        throw new IllegalStateException("Empty distribution");
    }
    public static List<String> board(boolean free, RandomSource random) {
        JsonNode mode = DATA.get("modes").get(free ? "free" : "paid");
        String shape = choose(mode.get("shapes"), random);
        List<String> board = new ArrayList<String>(15);
        for (int reel=0; reel<5; reel++) {
            String window = choose(mode.get("reels").get(reel).get(shape.substring(reel*2,reel*2+2)), random);
            for (int i=0;i<3;i++) board.add(GameRules.SYMBOLS.get(Character.digit(window.charAt(i),16)));
        }
        return board;
    }
    public static int[] initial(int scatters, RandomSource random) {
        JsonNode choices = DATA.get("initialFreeChoices").get(Integer.toString(scatters));
        if (choices == null) throw new IllegalArgumentException("No evidenced initial distribution for scatter count");
        String[] values = choose(choices, random).split(",");
        return new int[]{Integer.parseInt(values[0]),Integer.parseInt(values[1])};
    }
    public static void checkGeneratedBoard(List<String> board, boolean free) {
        JsonNode mode = DATA.get("modes").get(free ? "free" : "paid");
        StringBuilder shape = new StringBuilder();
        for (int reel=0;reel<5;reel++) {
            int wild=0,scatter=0;
            for (int row=0;row<3;row++) {
                String s=board.get(reel*3+row);
                if (GameRules.WILD.equals(s)) wild++;
                if (GameRules.SCATTER.equals(s)) scatter++;
            }
            shape.append(wild).append(scatter);
        }
        if (!mode.get("shapes").has(shape.toString())) throw new IllegalArgumentException("Unevidenced joint special-symbol shape");
    }

    private static final java.util.concurrent.ConcurrentMap<String,List<String>> LOSS_WINDOWS=new java.util.concurrent.ConcurrentHashMap<String,List<String>>();
    public static List<String> lossBoard(RandomSource random){
        List<String> b=new ArrayList<String>(15);int first=0;
        for(int c=0;c<5;c++){
            final int reel=c,forbidden=c==1?first:0;
            List<String> pool=LOSS_WINDOWS.computeIfAbsent(c+":"+forbidden,key->{
                List<String> out=new ArrayList<String>();Iterator<Map.Entry<String,JsonNode>> it=DATA.get("modes").get("paid").get("reels").get(reel).get("00").fields();
                while(it.hasNext()){
                    Map.Entry<String,JsonNode> e=it.next();boolean safe=true;
                    for(char v:e.getKey().toCharArray()){int id=Character.digit(v,16);if(id==10||id==12||(forbidden&(1<<id))!=0)safe=false;}
                    if(safe)for(int n=0;n<e.getValue().asInt();n++)out.add(e.getKey());
                }
                if(out.isEmpty())throw new IllegalStateException("loss-conditioned reel support empty");return Collections.unmodifiableList(out);
            });
            String window=pool.get(random.nextInt(pool.size()));for(char v:window.toCharArray()){int id=Character.digit(v,16);b.add(GameRules.SYMBOLS.get(id));if(c==0)first|=1<<id;}
        }
        return Collections.unmodifiableList(b);
    }
}
