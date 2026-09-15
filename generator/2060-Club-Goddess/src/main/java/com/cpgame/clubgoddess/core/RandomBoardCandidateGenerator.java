package com.cpgame.clubgoddess.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** New boards from aggregate, entry-specific three-cell column distributions. */
public final class RandomBoardCandidateGenerator implements BoardCandidateGenerator {
    private static final JsonNode MODEL = loadModel();
    public static final int MAX_CUMULATIVE_WILDS = MODEL.path("maxCumulativeWilds").asInt();
    private final RandomGenerator random;

    public RandomBoardCandidateGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }
    @Override public List<Integer> nextBoard() { return draw("paid", 2); }
    @Override public List<Integer> nextFreeBoard(int remainingWildBudget) {
        if (remainingWildBudget < 0) throw new IllegalArgumentException("negative Wild budget");
        return draw("free", Math.min(2, remainingWildBudget));
    }
    private List<Integer> draw(String entry, int wildBudget) {
        JsonNode model = MODEL.path(entry);
        String signature = select(model.path("signatures"), wildBudget, true);
        List<Integer> board = new ArrayList<>(15);
        for (int reel = 0; reel < 5; reel++) {
            String counts = signature.substring(reel * 2, reel * 2 + 2);
            String column = select(model.path("columns").get(reel).path(counts), 0, false);
            for (int row = 0; row < 3; row++) {
                char value = column.charAt(row);
                board.add(value == 'A' ? 10 : value - '0');
            }
        }
        return List.copyOf(board);
    }
    private String select(JsonNode histogram, int wildBudget, boolean signatures) {
        int total = 0;
        var fields = histogram.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            if (!signatures || wilds(e.getKey()) <= wildBudget) total += e.getValue().asInt();
        }
        if (total <= 0) throw new IllegalStateException("empty empirical model bucket");
        int pick = random.nextInt(total);
        fields = histogram.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            if (signatures && wilds(e.getKey()) > wildBudget) continue;
            pick -= e.getValue().asInt();
            if (pick < 0) return e.getKey();
        }
        throw new IllegalStateException("invalid model histogram");
    }
    private static int wilds(String signature) {
        int n = 0;
        for (int i = 1; i < signature.length(); i += 2) n += signature.charAt(i) - '0';
        return n;
    }
    private static JsonNode loadModel() {
        try (InputStream in = RandomBoardCandidateGenerator.class.getResourceAsStream("reel-model.json")) {
            if (in == null) throw new IllegalStateException("bundled 2060 reel model missing");
            JsonNode model = new ObjectMapper().readTree(in);
            if (model.path("gameId").asInt() != 2060 || model.path("schemaVersion").asInt() != 1)
                throw new IllegalStateException("wrong reel model identity");
            for (String mode : List.of("paid", "free")) {
                JsonNode entry = model.path(mode);
                if (entry.path("samples").asInt() <= 0 || entry.path("columns").size() != 5)
                    throw new IllegalStateException("incomplete reel model");
            }
            return model;
        } catch (java.io.IOException e) { throw new IllegalStateException("cannot load bundled reel model", e); }
    }

    private static final java.util.concurrent.ConcurrentMap<String,List<String>> LOSS_WINDOWS=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public List<Integer> nextLossBoard(){
        List<Integer> board=new ArrayList<>(15);int first=0;
        for(int c=0;c<5;c++){
            final int reel=c, forbidden=c==1?first:0;
            List<String> windows=LOSS_WINDOWS.computeIfAbsent(c+":"+forbidden,key->{
                List<String> pool=new ArrayList<>();var it=MODEL.path("paid").path("columns").get(reel).path("00").fields();
                while(it.hasNext()){
                    var e=it.next();boolean safe=true;for(char v:e.getKey().toCharArray())if(v<'1'||v>'8'||(forbidden&(1<<(v-'0')))!=0)safe=false;
                    if(safe)for(int n=0;n<e.getValue().asInt();n++)pool.add(e.getKey());
                }
                if(pool.isEmpty())throw new IllegalStateException("no loss-conditioned reel support");return List.copyOf(pool);
            });
            String window=windows.get(random.nextInt(windows.size()));for(char v:window.toCharArray()){board.add(v-'0');if(c==0)first|=1<<(v-'0');}
        }
        return List.copyOf(board);
    }
}
