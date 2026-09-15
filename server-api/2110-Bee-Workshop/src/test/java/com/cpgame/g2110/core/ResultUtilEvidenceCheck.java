package com.cpgame.g2110.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Raw evidence regression, runnable with the existing shaded JAR and a JDK. */
public final class ResultUtilEvidenceCheck {
    private static String read(Path path,ExecutorService reader) throws Exception {
        var pending=reader.submit(()->Files.readString(path));
        try{return pending.get(10,TimeUnit.SECONDS);}
        catch(TimeoutException timeout){
            pending.cancel(true);
            throw new IllegalStateException("IO_TIMEOUT: bounded read 10s: "+path,timeout);
        }
    }

    public static int verify(Path root) throws Exception {
        ExecutorService reader=Executors.newSingleThreadExecutor(r->{
            Thread thread=new Thread(r,"bee-evidence-reader");thread.setDaemon(true);return thread;
        });
        try{
            ObjectMapper json=new ObjectMapper();
            ResultUtil util=new ResultUtil(new GameRuleCore());
            var seen=new HashSet<String>();
            int checked=0,positive=0;
            for(String entry:read(root.resolve("round-index.jsonl"),reader).split("\\R")){
                if(entry.isBlank())continue;
                String id=json.readTree(entry).path("roundId").asText();
                if(!id.matches("round-\\d+"))throw new IllegalArgumentException("invalid indexed path");
                if(!seen.add(id))continue;
                Path response=root.resolve(id).resolve("step-001.response.json");
                JsonNode data=json.readTree(read(response,reader)).path("data");
                int[] board=json.treeToValue(data.path("props").path("prop"),int[].class);
                long expected=0;
                for(JsonNode win:data.path("props").path("win_arr"))expected+=win.path("odd").asLong();
                long actual=util.independentPayoutUnits(board);
                double money=util.moneyFromUnits(actual,data.path("bet").asDouble(),data.path("level").asInt());
                if(expected!=actual||Math.abs(data.path("props").path("tw").asDouble()-money)>0.00001)
                    throw new IllegalStateException("original payout mismatch: "+response);
                if(expected>0)positive++;
                checked++;
                if(checked%100==0)System.out.println("original responses verified: "+checked);
            }
            if(checked!=1476||positive<=100)throw new IllegalStateException("unexpected evidence coverage: "+checked+", paying="+positive);
            System.out.println("{\"test\":\"ResultUtil raw evidence\",\"status\":\"PASS\",\"responses\":"+checked+",\"payingResponses\":"+positive+"}");
            return checked;
        }finally{reader.shutdownNow();}
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("expected the original spin fixture directory");
        verify(Path.of(args[0]));
    }
}
