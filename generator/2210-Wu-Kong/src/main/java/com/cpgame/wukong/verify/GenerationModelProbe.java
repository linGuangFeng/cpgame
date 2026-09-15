package com.cpgame.wukong.verify;

import com.cpgame.wukong.core.*;
import java.io.InputStream;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** 生成独立大样本观测值；不读取原厂留出集，也不产生期望值。 */
public final class GenerationModelProbe {
    public static void main(String[] args)throws Exception{Path p=Path.of(args.length>0?args[0]:"packaging/generator.properties");int count=args.length>1?Integer.parseInt(args[1]):10000;Properties properties=new Properties();try(InputStream in=Files.newInputStream(p)){properties.load(in);}RoundGenerator g=new RoundGenerator(new SecureRandom(),properties);GameRuleCore rules=new GameRuleCore();ResultUtil results=new ResultUtil(rules);Map<String,Integer> modes=new TreeMap<>(),outcomes=new TreeMap<>(),initial=new TreeMap<>(),respin=new TreeMap<>();for(int i=0;i<count;i++){CompleteRound r=g.generate();rules.validateHardCaps(r);results.evaluate(r);modes.merge(r.mode().name(),1,Integer::sum);outcomes.merge(rules.classify(r).name(),1,Integer::sum);initial.merge(r.mode()+":"+r.initial().left()+","+r.initial().right(),1,Integer::sum);if(r.respin()!=null)respin.merge(r.respin().left()+","+r.respin().right(),1,Integer::sum);}System.out.println("{\"count\":"+count+",\"modes\":"+json(modes)+",\"outcomes\":"+json(outcomes)+",\"initialJoint\":"+json(initial)+",\"respinJoint\":"+json(respin)+"}");}
    private static String json(Map<String,Integer> map){StringJoiner j=new StringJoiner(",","{","}");map.forEach((k,v)->j.add("\""+k+"\":"+v));return j.toString();}
}
