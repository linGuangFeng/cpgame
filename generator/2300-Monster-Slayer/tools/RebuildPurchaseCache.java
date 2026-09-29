import com.cpgame.monsterslayer.core.*;
import com.cpgame.monsterslayer.generator.*;
import com.cpgame.monsterslayer.redis.*;
import java.nio.file.*;
import java.util.*;

/** Explicit, backed-up, atomic MS4 purchase-pool migration. Never touches ordinary pools. */
class RebuildPurchaseCache {
 public static void main(String[] args) throws Exception {
  if(args.length!=3||!args[2].equals("--apply"))throw new IllegalArgumentException("Usage: <generator.properties> <validated cache.tsv> --apply");
  var config=GeneratorConfig.load(Path.of(args[0]));var codec=new MinimalRoundFactCodec();
  Map<String,List<String>> lists=new TreeMap<>();Map<String,Set<Integer>> indexes=new TreeMap<>();int count=0;
  for(String line:Files.readAllLines(Path.of(args[1]))){var f=line.split("\t",3);int mode=Integer.parseInt(f[0]),m=Integer.parseInt(f[1]);
   if(!f[2].startsWith("MS4"))throw new IllegalArgumentException("Only rule-generated MS4 accepted");
   var r=codec.decode(f[2]);if(r.buyType()!=mode||ResultUtil.redisMultiplierCenti(r)!=m||!config.outputLimits.accepts(true,m)||m>config.maxCentiMultiplier)throw new IllegalArgumentException("Invalid mode/payout/limits");
   int streak=0;for(var step:ResultUtil.evaluate(r).steps()){streak=step.multiplierCenti()>0?streak+1:0;if(streak>config.maxConsecutiveWins)throw new IllegalArgumentException("win streak cap");}if(r.steps().size()>config.maxMarySpins)throw new IllegalArgumentException("step cap");
   lists.computeIfAbsent(RedisKeyContract.buyList(config.redisGameId,mode,m),k->new ArrayList<>()).add(f[2]);indexes.computeIfAbsent(RedisKeyContract.buyIndex(config.redisGameId,mode),k->new TreeSet<>()).add(m);count++;
  }
  if(indexes.size()!=3)throw new IllegalArgumentException("All three purchase pools required");
  for(var l:lists.values())if(l.size()>config.specialMaxMembersPerMultiplier)throw new IllegalArgumentException("bucket cap");
  String run=Long.toString(System.currentTimeMillis()),stage="MS4:stage:"+run+":",backup="MS4:backup:"+run+":";
  try(var redis=RedisConnection.connect(config)){
   Set<String> oldKeys=new TreeSet<>(indexes.keySet());
   for(int mode:new int[]{3,4,5})for(Object v:(List<?>)redis.command("ZRANGE",RedisKeyContract.buyIndex(config.redisGameId,mode),"0","-1"))oldKeys.add(RedisKeyContract.buyList(config.redisGameId,mode,Integer.parseInt(v.toString())));
   Set<String> allKeys=new TreeSet<>(oldKeys);allKeys.addAll(lists.keySet());
   List<String> keys=new ArrayList<>(allKeys);
   String fingerprint="local out={} for _,k in ipairs(KEYS) do local d=redis.call('DUMP',k); out[#out+1]=d and redis.sha1hex(d) or '-' end return out";
   List<String> command=new ArrayList<>(List.of("EVAL",fingerprint,Integer.toString(keys.size())));command.addAll(keys);
   var fingerprints=(List<?>)redis.command(command.toArray(String[]::new));
   List<String[]> batch=new ArrayList<>();
   for(var e:lists.entrySet()){var cmd=new ArrayList<>(List.of("RPUSH",stage+e.getKey()));cmd.addAll(e.getValue());batch.add(cmd.toArray(String[]::new));batch.add(new String[]{"EXPIRE",stage+e.getKey(),"172800"});if(batch.size()>=100){redis.transaction(batch);batch.clear();}}
   for(var e:indexes.entrySet()){var cmd=new ArrayList<>(List.of("ZADD",stage+e.getKey()));for(int m:e.getValue()){cmd.add(Integer.toString(m));cmd.add(Integer.toString(m));}batch.add(cmd.toArray(String[]::new));batch.add(new String[]{"EXPIRE",stage+e.getKey(),"172800"});}
   if(!batch.isEmpty())redis.transaction(batch);
   // All preconditions precede mutations. Abort if any old/new destination changed during staging.
   String swap="for i,k in ipairs(KEYS) do local d=redis.call('DUMP',k); local h=d and redis.sha1hex(d) or '-'; if h~=ARGV[i+2] then return redis.error_reply('Cache changed during staging; stop old writer and retry') end; if redis.call('EXISTS',ARGV[2]..k)==1 then return redis.error_reply('Backup exists') end end; for _,k in ipairs(KEYS) do if redis.call('EXISTS',k)==1 then redis.call('RENAME',k,ARGV[2]..k) end; if redis.call('EXISTS',ARGV[1]..k)==1 then redis.call('RENAME',ARGV[1]..k,k);redis.call('PERSIST',k) end end return #KEYS";
   command=new ArrayList<>(List.of("EVAL",swap,Integer.toString(keys.size())));command.addAll(keys);command.add(stage);command.add(backup);for(Object h:fingerprints)command.add(h.toString());
   Object changed=redis.command(command.toArray(String[]::new));
   System.out.println("MIGRATED members="+count+" buckets="+lists.size()+" keys="+changed+" backupPrefix="+backup+" ordinaryUntouched=true");
  }
 }
}
