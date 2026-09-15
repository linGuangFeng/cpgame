package com.cpgame.replica.beeworkshop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Aggregate evidence parameters only; no complete source board, response or round is stored. */
public final class EmpiricalDealModel {
    public static final String SHA256="3d5f4e2805cd49bda59a53a06232f40385e8a0de1f7db1c4263e8e9adf39fa85";
    private final JsonNode data;
    private final Map<String,WeightedTable> tupleTables=new ConcurrentHashMap<>();
    private static final class Holder { private static final EmpiricalDealModel INSTANCE=new EmpiricalDealModel(); }
    public static EmpiricalDealModel shared(){return Holder.INSTANCE;}

    private EmpiricalDealModel(){
        try(InputStream input=EmpiricalDealModel.class.getResourceAsStream("deal-model.json")){
            if(input==null)throw new IllegalStateException("Missing packaged Bee Workshop deal model");
            byte[] bytes=input.readNBytes(2*1024*1024+1);
            if(bytes.length>2*1024*1024||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalStateException("Bee Workshop deal model checksum mismatch");
            data=new ObjectMapper().readTree(bytes);
            if(data.path("schemaVersion").asInt()!=2||data.path("gameId").asInt()!=2110)
                throw new IllegalStateException("Unsupported Bee Workshop deal model");
        }catch(Exception failure){throw new IllegalStateException("Cannot load verified Bee Workshop deal model",failure);}
    }

    private JsonNode entry(String name){
        JsonNode entry=data.path("entries").path(name);
        if(entry.isMissingNode()||entry.path("sampleCount").asInt()<=0)throw new IllegalStateException("No empirical entry: "+name);
        return entry;
    }
    private static JsonNode draw(List<JsonNode> candidates,Random random){
        int total=0;
        for(JsonNode candidate:candidates)total=Math.addExact(total,candidate.path("count").asInt());
        if(total<=0)return null;
        int draw=random.nextInt(total);
        for(JsonNode candidate:candidates){draw-=candidate.path("count").asInt();if(draw<0)return candidate;}
        throw new IllegalStateException("Invalid empirical weights");
    }
    private static List<JsonNode> values(JsonNode array){
        List<JsonNode> values=new ArrayList<>();array.forEach(values::add);return values;
    }
    private static final class WeightedTable {
        private final List<JsonNode> values;
        private final int[] cumulative;
        private final int total;
        WeightedTable(List<JsonNode> values){
            this.values=List.copyOf(values);cumulative=new int[values.size()];int sum=0;
            for(int i=0;i<values.size();i++){sum=Math.addExact(sum,values.get(i).path("count").asInt());cumulative[i]=sum;}
            total=sum;
        }
        JsonNode draw(Random random){
            if(total<=0)return null;int value=random.nextInt(total),low=0,high=cumulative.length-1;
            while(low<high){int mid=(low+high)>>>1;if(value<cumulative[mid])high=mid;else low=mid+1;}
            return values.get(low);
        }
    }

    /** Deal a whole column tuple. Never select symbols independently per cell. */
    public int[] board(String name,int overlayMask,boolean exactOverlayMask,Random random){
        JsonNode reels=entry(name).path("reels");
        if(reels.size()!=5)throw new IllegalStateException("No background evidence for entry "+name);
        int[] board=new int[15];
        for(int reel=0;reel<5;reel++){
            int desired=(overlayMask>>(reel*3))&7;
            final int sourceReel=reel;
            WeightedTable table=tupleTables.computeIfAbsent(name+":"+reel+":"+desired+":"+exactOverlayMask,key->{
              List<JsonNode> eligible=new ArrayList<>();
              for(JsonNode tuple:reels.get(sourceReel)){
                JsonNode symbols=tuple.path("symbols");int unknown=0;
                for(int row=0;row<3;row++)if(symbols.get(row).asInt()==0)unknown|=1<<row;
                // A source's hidden cells must already be covered by this feature.
                if(exactOverlayMask?unknown==desired:(unknown&~desired)==0)eligible.add(tuple);
              }
              return new WeightedTable(eligible);
            });
            JsonNode tuple=table.draw(random);
            if(tuple==null)return null;
            for(int row=0;row<3;row++)board[reel*3+row]=(desired&(1<<row))!=0?0:tuple.path("symbols").get(row).asInt();
        }
        return board;
    }

    public Overlay mystery(Random random){
        JsonNode choice=draw(values(entry("MYSTERY_BOX").path("overlays")),random);
        if(choice==null)throw new IllegalStateException("Missing mystery overlay model");
        List<Integer> positions=new ArrayList<>();choice.path("positions").forEach(p->positions.add(p.asInt()));
        return new Overlay(List.copyOf(positions),choice.path("symbol").asInt());
    }
    public int target(String entry,Random random){
        JsonNode choice=draw(values(entry(entry).path("targets")),random);
        if(choice==null)throw new IllegalStateException("Missing reveal symbol model for "+entry);
        return choice.path("symbol").asInt();
    }
    public int triggerScatterCount(Random random){
        JsonNode choice=draw(values(entry("FREE_TRIGGER").path("scatterAwards")),random);
        if(choice==null)throw new IllegalStateException("Missing Scatter trigger model");
        return choice.path("scatterAndAward").get(0).asInt();
    }
    public Map<Integer,Integer> triggerCounts(){
        Map<Integer,Integer> counts=new java.util.TreeMap<>();
        for(JsonNode choice:entry("FREE_TRIGGER").path("scatterAwards"))counts.merge(choice.path("scatterAndAward").get(0).asInt(),choice.path("count").asInt(),Integer::sum);
        return Map.copyOf(counts);
    }
    public Bounds bounds(String entry){
        JsonNode bounds=data.path("caps").path(entry);
        if(bounds.isMissingNode())throw new IllegalStateException("Missing placement bounds: "+entry);
        int[] wild=new int[5],scatter=new int[5];
        for(int i=0;i<5;i++){wild[i]=bounds.path("wildReelMax").get(i).asInt();scatter[i]=bounds.path("scatterReelMax").get(i).asInt();}
        return new Bounds(bounds.path("wildBoardMax").asInt(),bounds.path("scatterBoardMax").asInt(),wild,scatter,
                bounds.path("specialMin").asInt(),bounds.path("specialMax").asInt());
    }
    public int newStickyLimit(boolean initial){return data.path(initial?"freeInitialNewStickyMax":"freeContinuationNewStickyMax").asInt();}
    public record Overlay(List<Integer> positions,int symbol){}
    public record Bounds(int wildBoardMax,int scatterBoardMax,int[] wildPerReel,int[] scatterPerReel,int specialMin,int specialMax){}
}
