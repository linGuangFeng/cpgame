package com.cpgame.monsterslayer;
import com.cpgame.monsterslayer.generator.*;import com.cpgame.monsterslayer.core.*;import com.fasterxml.jackson.databind.*;import java.nio.file.*;import java.util.*;import java.security.*;
public class RuleDrivenHuntRegression {
 static final ObjectMapper J=new ObjectMapper();
 public static void main(String[] args)throws Exception{
  JsonNode rows=J.readTree(Path.of(args[0]).toFile());int passed=0;List<String>bad=new ArrayList<>();
  for(JsonNode row:rows){var f=row.path("feature");var loc=f.path("loc");var cells=new ArrayList<Integer>();var ids=new ArrayList<Integer>();loc.fields().forEachRemaining(e->{cells.add(Integer.parseInt(e.getKey()));ids.add(e.getValue().asInt());});var hits=new ArrayList<Integer>();var up=new ArrayList<Integer>();var types=new ArrayList<Integer>();for(var a:f.path("a")){hits.add(a.path("bl").asInt());up.add(a.path("iu").asInt());types.add(a.path("t").asInt());}
   var facts=new GameRuleCore.FeatureFacts(ints(f.path("h")),arr(cells),arr(ids),arr(hits),arr(up),arr(types),ints(f.path("rbs")),f.path("r").asText("").getBytes(java.nio.charset.StandardCharsets.UTF_8));var step=new GameRuleCore.Step(ints(row.path("board")),f.path("gt").asInt(),f.path("nt").asInt(),facts);
   int got=ResultUtil.evaluateStep(step).multiplierCenti();if(got!=row.path("expected").asInt()){if(bad.size()<12)bad.add(row.path("source").asText()+" expected="+row.path("expected")+" got="+got);}else passed++;
  }
  System.out.println("ORIGINAL matched="+passed+" total="+rows.size()+" failures="+(rows.size()-passed)+" examples="+bad);if(passed!=rows.size())throw new AssertionError("original payouts differ");
  var factory=new CompleteRoundFactory();var codec=new MinimalRoundFactCodec();var random=new SecureRandom();var output=J.createObjectNode();output.put("originalSteps",passed);var modes=output.putArray("modes");
  try(var generated=Files.newBufferedWriter(Path.of(args[1]))){for(int mode:new int[]{0,3,4,5}){Set<Integer>ratios=new HashSet<>();Set<String>unique=new HashSet<>();int maximum=0,high=0,within=0,reward=0;
   for(int i=0;i<2500;i++){var r=mode==0?factory.generateSpecial(random):factory.generateBuy(mode,random);int total=0;for(var step:r.steps()){int independent=oracle(step);int actual=ResultUtil.evaluateStep(step).multiplierCenti();if(independent!=actual)throw new AssertionError("independent payout mismatch");total+=independent;if(step.gameType()==4)reward++;}
    if(total!=ResultUtil.redisMultiplierCenti(r))throw new AssertionError("round total");maximum=Math.max(maximum,total);ratios.add(total);if(total>=10000)high++;if(total>=1&&total<=30000)within++;
    byte[] member=codec.encode(r);String hex=java.util.HexFormat.of().formatHex(member);if(!unique.add(hex))throw new AssertionError("duplicate generated round");if(ResultUtil.redisMultiplierCenti(codec.decode(member))!=total)throw new AssertionError("codec mismatch");generated.write(mode+"\t"+total+"\t"+hex+"\n");
   }
   var n=modes.addObject();n.put("mode",mode);n.put("rounds",2500);n.put("distinctMultipliers",ratios.size());n.put("maximumCenti",maximum);n.put("atLeast10000",high);n.put("withinConfigured30000",within);n.put("rewardSteps",reward);System.out.println(n);
  }}Files.writeString(Path.of(args[2]),J.writerWithDefaultPrettyPrinter().writeValueAsString(output));
 }
 static int[] ints(JsonNode a){int[]b=new int[a.size()];for(int i=0;i<b.length;i++)b[i]=a.get(i).asInt();return b;}static int[]arr(List<Integer>x){return x.stream().mapToInt(Integer::intValue).toArray();}
 static int oracle(GameRuleCore.Step step){
  int[][]pay={{},{50,100,750},{40,80,500},{35,50,250},{30,40,200},{30,40,150},{25,35,150},{20,30,125},{20,30,100},{15,25,75},{10,25,50}};
  int[]b=step.board(),factor=new int[15];Arrays.fill(factor,1);int global=1;JsonNode last=null;var roles=HuntTrace.roles(step.feature().roles());Set<Integer> cuts=new HashSet<>();int bottom=0;
  for(var role:roles)for(var a:role){last=a;global=a.path("f").path("m").asInt(1);for(var cell:a.path("sp").path("2"))cuts.add(cell.asInt());if(a.path("ls").path("ln").asInt()==3)bottom++;}
  for(int c:cuts)factor[c]*=2;
  if(last!=null){boolean up=false;for(var a:last.path("f").path("a"))if(a.path("t").asInt()==1&&a.path("iu").asInt()==1)up=true;
   if(step.gameType()==4)global*=1<<bottom;else if(up){var it=last.path("af").fields();while(it.hasNext()){var e=it.next();if(e.getValue().asInt()==1)factor[Integer.parseInt(e.getKey())]*=2;}}
  }
  int total=0;
  for(int symbol=1;symbol<=10;symbol++){
   int[] paths=new int[3];for(int row=0;row<3;row++)if(b[row]==symbol)paths[row]=factor[row];
   for(int col=0;col<5;col++){
    int[] next=new int[3];
    for(int row=0;row<3;row++)if(paths[row]>0){boolean extendsPath=false;if(col<4)for(int nr=Math.max(0,row-1);nr<=Math.min(2,row+1);nr++){int c=(col+1)*3+nr;if(b[c]==symbol||b[c]==0){next[nr]+=paths[row]*factor[c];extendsPath=true;}}
     if(!extendsPath&&col>=2)total+=paths[row]*pay[symbol][col-2];
    }paths=next;
   }
  }return total*global;
 }
}
