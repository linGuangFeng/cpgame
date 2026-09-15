package com.hd.cpgame.jungleparty;

import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Emits observed features only; independent validation and expected values live outside the implementation. */
public final class GenerationSampleMain {
    private GenerationSampleMain() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("usage: GenerationSampleMain <round-count> <output.tsv>");
        int count=Integer.parseInt(args[0]);if(count<10000)throw new IllegalArgumentException("round-count must be at least 10000");
        SecureRandom random=SecureRandom.getInstance("SHA1PRNG");random.setSeed(330037L);
        try(BufferedWriter out=Files.newBufferedWriter(Path.of(args[1]),StandardCharsets.UTF_8)){
            out.write("MODEL\t"+GenerationModel.MODEL_HASH+"\t"+count+"\n");
            for(int i=1;i<=count;i++){
                GameRuleCore.Round round=GameRuleCore.generate(random,GameRuleCore.Scenario.RANDOM,1,new BigDecimal("0.02"));
                String outcome=switch(round.scenario()){case ORDINARY_LOSS->"LOSS";case ORDINARY_WIN->"WIN";case SCATTER_FREE_ROUNDS->"FREE";case RANDOM->throw new IllegalStateException();};
                out.write("R\t"+i+"\t"+outcome+"\t"+round.deliveries().size()+"\t"+(round.deliveries().isEmpty()?0:round.deliveries().get(0).rpx())+"\n");
                for(GameRuleCore.Delivery d:round.deliveries()){
                    int[] counts=new int[GameRuleCore.Symbol.values().length];for(GameRuleCore.Symbol s:d.board().cells())counts[s.ordinal()]++;
                    out.write("B\t"+i+"\t"+(d.index()==0?"INITIAL":"FREE_SPIN")+"\t");
                    for(int j=0;j<counts.length;j++){if(j>0)out.write(",");out.write(Integer.toString(counts[j]));}
                    out.write("\t"+String.join(",",d.board().externalCells())+"\n");
                }
            }
        }
        System.out.println("GenerationSampleMain PASS "+count+" complete Rounds");
    }
}
