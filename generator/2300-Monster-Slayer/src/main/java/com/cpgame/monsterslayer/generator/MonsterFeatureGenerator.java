package com.cpgame.monsterslayer.generator;

import com.cpgame.monsterslayer.core.*;
import java.security.SecureRandom;
import java.util.*;

/** Fresh rounds from the original hunting state machine; no captured round source. */
public final class MonsterFeatureGenerator {
    private final FeatureReelModel model;
    public MonsterFeatureGenerator(){model=new FeatureReelModel();}
    public GameRuleCore.CompleteRound generate(int buy,SecureRandom random){
        if(buy!=0&&buy!=3&&buy!=4&&buy!=5)throw new IllegalArgumentException("buy type must be 0/3/4/5");
        // Reject overlong complete attempts. Never truncate a live feature or invent terminal hearts.
        for(int attempt=0;attempt<200;attempt++){
            GameRuleCore.CompleteRound result=attempt(buy,random);
            if(result!=null)return result;
        }
        throw new IllegalStateException("hunting generator exhausted 200 bounded complete attempts");
    }
    private GameRuleCore.CompleteRound attempt(int buy,SecureRandom r){
        State s=new State();s.buy=buy;
        s.mode=buy==4?3:buy==0&&r.nextInt(50)<13?1:2;
        s.hearts=new int[s.mode==1?2:3];Arrays.fill(s.hearts,1);
        s.types=s.mode==1?(r.nextBoolean()?new int[]{1,2}:new int[]{1,3}):new int[]{1,2,3};
        if(buy==4){Arrays.fill(s.up,true);Arrays.fill(s.hearts,2);}
        if(buy==5){
            // First 15 captured purchases: 5/7/3 starts with 0/1/2 upgraded monsters.
            // Monster identity is randomized; array position is not a monster type.
            int draw=r.nextInt(15),upgrades=draw<5?0:draw<12?1:2;
            List<Integer> chosen=new ArrayList<>(List.of(1,2,3));Collections.shuffle(chosen,r);
            for(int i=0;i<upgrades;i++){s.up[chosen.get(i)]=true;s.hearts[i]=2;}
        }
        List<GameRuleCore.Step> steps=new ArrayList<>();int[] opening=model.board(true,r);
        // One Scatter per inner reel. Type 1 has two; type 2/3 has three.
        List<Integer> reels=new ArrayList<>(List.of(1,2,3));Collections.shuffle(reels,r);
        for(int i=0;i<s.types.length;i++){int cell=reels.get(i)*3+r.nextInt(3);s.pos[s.types[i]]=cell;opening[cell]=100;}
        steps.add(new GameRuleCore.Step(opening,s.mode,s.mode,facts(s,s.hearts,loc(s),s.hits,s.up,treePath(s), new byte[0])));
        for(int spin=0;spin<31;spin++){
            s.spin=s.turn;int mode=s.mode;
            if(mode==4){var reward=reward(s,r);if(Arrays.stream(reward.board()).filter(v->v==0).count()>9)return null;steps.add(reward);continue;}
            move(s,r);
            int[] startLoc=loc(s),startHits=s.hits.clone();boolean[] startUp=s.up.clone();
            int[] board=model.board(false,r);List<Integer> trail=new ArrayList<>();if(s.pos[3]>=0)trail.add(s.pos[3]);
            int[] collision=startLoc.clone();Set<Integer> split=new LinkedHashSet<>(),wild1=new LinkedHashSet<>(),wild2=new LinkedHashSet<>();
            List<HuntTrace.Action> actions=new ArrayList<>();
            int weaponCount=mode==3||(s.spin+1)%5==0?2:1;
            for(int weapon=1;weapon<=weaponCount;weapon++){
                int health=weapon;
                for(int direction=1;direction<=2&&health>0;direction++){
                    int row=attackRow(mode,weapon,collision,r),beforeHealth=health;boolean hit=false;int[] before=loc(s);
                    for(int col=direction==1?4:0;col>=0&&col<5&&health>0;col+=direction==1?-1:1){
                        int cell=col*3+row,type=collision[cell];if(type==0)continue;
                        collision[cell]=0;hit=true;
                        if(type==1)health=0;else health--;
                        if(type<=3){
                            if(health==0&&(mode==3||s.hits[type]<5))s.hits[type]++;
                            if(!s.up[type]&&s.hits[type]>=5){s.up[type]=true;upgradeHeart(s.hearts);}
                            if(type==2)for(int c=direction==1?4:0;direction==1?c>col:c<col;c+=direction==1?-1:1)
                                for(int rr=0;rr<3;rr++)if(s.up[2]||rr==row)split.add(c*3+rr);
                            if(type==3){
                                (weapon==1?wild1:wild2).add(cell);
                                List<Integer> empty=new ArrayList<>();for(int c=3;c<12;c++)if(collision[c]==0&&c!=cell&&!wild1.contains(c)&&!wild2.contains(c)&&!containsMonster(s,c))empty.add(c);
                                if(empty.isEmpty())return null;
                                int next=empty.get(r.nextInt(empty.size()));s.pos[3]=next;collision[next]=3;trail.add(next);
                            }
                        }else{if(health==0&&(mode==3||s.hits[3]<5))s.hits[3]++;(weapon==1?wild1:wild2).add(cell);s.trap=-1;}
                    }
                    if(direction==2&&health>0)damageHeart(s.hearts);
                    actions.add(new HuntTrace.Action(weapon,direction,row,hit?1:0,beforeHealth,multiplier(s),mode,before,loc(s),s.hearts,animals(s),array(split),array(wild1),array(wild2)));
                }
            }
            // Feature overlays are settled after all attacks. Split symbols stay their natural ID.
            for(int t:s.types)board[s.pos[t]]=0;
            for(int c:trail)board[c]=0;
            for(int c:wild1)board[c]=0;for(int c:wild2)board[c]=0;
            int next=Arrays.stream(s.hearts).sum()==0?0:mode;
            if(next!=0&&mode==2&&s.up[1]&&s.up[2]&&s.up[3])next=3;
            if(next!=0&&mode==3&&s.hits[1]+s.hits[2]+s.hits[3]>=20)next=4;
            if(!withinObserved(s,trail.size()) || Arrays.stream(board).filter(v->v==0).count()>9)return null;
            var trace=new HuntTrace.Trace(s.spin,mode,buy,withNext(actions,next));
            steps.add(new GameRuleCore.Step(board,mode,next,facts(s,s.hearts,startLoc,startHits,startUp,array(trail),HuntTrace.encode(trace))));
            if(next==0)return new GameRuleCore.CompleteRound(true,buy,steps);
            if(mode==1&&next==2){s.types=new int[]{1,2,3};int missing=s.pos[2]<0?2:3;for(int c=3;c<12;c++)if(board[c]==100){s.pos[missing]=c;break;}s.hearts=restore(s.hearts,3);}
            if(mode==2&&next==3){Arrays.fill(s.hits,0);s.hearts=restore(s.hearts,3);}
            s.turn=mode==2&&next==3?0:s.turn+1;
            s.mode=next;
            if(!withinObserved(s,trail.size()))return null;
        }
        return null;
    }
    private GameRuleCore.Step reward(State s,SecureRandom r){
        int[] oldHits=s.hits.clone();boolean[] oldUp=s.up.clone();s.hearts=restore(s.hearts,3);s.pos[3]=6;s.pos[2]=7;s.pos[1]=8;s.trap=-1;
        int[] board=model.board(false,r);for(int c=3;c<12;c++)board[c]=0;
        List<HuntTrace.Action> actions=new ArrayList<>();Set<Integer> split=new LinkedHashSet<>(),wild=new LinkedHashSet<>();
        for(int w=1;w<=2;w++){
            int row=r.nextInt(3);
            if(row==0){List<Integer> free=new ArrayList<>();for(int c=12;c<15;c++)if(board[c]!=0)free.add(c);int c=free.get(r.nextInt(free.size()));board[c]=0;wild.add(c);}
            if(row==1)for(int c=12;c<15;c++)split.add(c);
            actions.add(new HuntTrace.Action(w,1,row,1,w,5,3,loc(s),loc(s),s.hearts,animals(s),array(split),array(wild),new int[0]));
        }
        var step=new GameRuleCore.Step(board,4,3,facts(s,s.hearts,loc(s),oldHits,oldUp,new int[0],HuntTrace.encode(new HuntTrace.Trace(s.spin,4,s.buy,actions))));
        Arrays.fill(s.hits,0);s.mode=3;s.turn=0;return step;
    }
    private static List<HuntTrace.Action> withNext(List<HuntTrace.Action> in,int next){List<HuntTrace.Action> out=new ArrayList<>();for(var a:in)out.add(new HuntTrace.Action(a.weapon(),a.direction(),a.row(),a.blocked(),a.health(),a.multiplier(),next,a.before(),a.after(),a.hearts(),a.animals(),a.split(),a.wild1(),a.wild2()));return out;}
    // Maximum-likelihood row weights from held-out-excluded original attacks.
    // A row is sampled jointly with current collision occupancy, not uniformly.
    // Per (mode, weapon) samples: 92/23, 993/227, 215/294. Empty-row weight is 1000.
    private static final int[][] OCCUPIED_WEIGHT={{0,0},{1179,651},{380,374},{269,265}};
    private static int attackRow(int mode,int weapon,int[] collision,SecureRandom r){
        int[] weights=new int[3];int total=0;
        for(int row=0;row<3;row++){boolean occupied=false;for(int col=1;col<=3;col++)occupied|=collision[col*3+row]>0;
            weights[row]=occupied?OCCUPIED_WEIGHT[mode][weapon-1]:1000;total+=weights[row];}
        int n=r.nextInt(total);for(int row=0;row<3;row++){n-=weights[row];if(n<0)return row;}throw new AssertionError();
    }
    private static boolean withinObserved(State s,int jumps){for(int t:s.types)if(s.hits[t]>11)return false;return jumps<=3;}
    private static void move(State s,SecureRandom r){
        s.trap=-1;
        for(int t:s.types){
            if(t==1&&s.spin%2==1)continue;
            List<Integer> cells=new ArrayList<>();int old=s.pos[t];
            for(int c=3;c<12;c++)if(!containsMonster(s,c)&&(t==3||Math.max(Math.abs(c/3-old/3),Math.abs(c%3-old%3))==1))cells.add(c);
            if(!cells.isEmpty())s.pos[t]=cells.get(r.nextInt(cells.size()));
        }
        if(s.up[3]&&s.pos[3]>=0){List<Integer> cells=new ArrayList<>();for(int c=3;c<12;c++)if(!containsMonster(s,c))cells.add(c);if(!cells.isEmpty())s.trap=cells.get(r.nextInt(cells.size()));}
    }
    private static boolean containsMonster(State s,int c){for(int t:s.types)if(s.pos[t]==c)return true;return false;}
    private static int[] loc(State s){int[] out=new int[15];for(int t:s.types)out[s.pos[t]]=t;if(s.trap>=0)out[s.trap]=4;return out;}
    private static int[] treePath(State s){return s.pos[3]<0?new int[0]:new int[]{s.pos[3]};}
    private static GameRuleCore.FeatureFacts facts(State s,int[] hearts,int[] loc,int[] hits,boolean[]up,int[]rbs,byte[] trace){
        int n=0;for(int x:loc)if(x>0)n++;int[]lc=new int[n],li=new int[n];int k=0;for(int i=0;i<loc.length;i++)if(loc[i]>0){lc[k]=i;li[k++]=loc[i];}
        int[] bl=new int[s.types.length],iu=new int[s.types.length];for(int i=0;i<s.types.length;i++){bl[i]=hits[s.types[i]];iu[i]=up[s.types[i]]?1:0;}
        return new GameRuleCore.FeatureFacts(hearts,lc,li,bl,iu,s.types,rbs,trace);
    }
    private static int[] animals(State s){int[] out=new int[s.types.length*3];int i=0;for(int t:s.types){out[i++]=t;out[i++]=s.hits[t];out[i++]=s.up[t]?1:0;}return out;}
    private static int multiplier(State s){return s.pos[1]<0?1:s.up[1]?5:Math.min(5,s.hits[1]+1);}
    private static void upgradeHeart(int[] h){for(int i=0;i<h.length;i++)if(h[i]<2){h[i]=2;return;}}
    private static void damageHeart(int[] h){for(int i=h.length-1;i>=0;i--)if(h[i]>0){h[i]--;return;}}
    private static int[] restore(int[] h,int n){int[] out=Arrays.copyOf(h,n);for(int i=0;i<n;i++)if(out[i]==0)out[i]=1;return out;}
    private static int[] array(Collection<Integer> c){return c.stream().mapToInt(Integer::intValue).toArray();}
    private static final class State {int mode,buy,spin,turn,trap=-1;int[]hearts,types;int[]hits=new int[4],pos={-1,-1,-1,-1};boolean[]up=new boolean[4];}
}
