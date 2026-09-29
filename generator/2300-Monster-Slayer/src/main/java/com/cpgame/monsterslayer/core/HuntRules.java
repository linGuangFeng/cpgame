package com.cpgame.monsterslayer.core;
import java.util.*;

/** Independent replay of stored weapon decisions. No RNG, generator, or trusted payout fields. */
public final class HuntRules {
    private HuntRules(){}
    public static void validate(GameRuleCore.CompleteRound round){
        if(round.steps().stream().noneMatch(s->HuntTrace.generated(s.feature().roles())))return;
        var first=round.steps().get(0);int mode=first.gameType();
        int[] hits=new int[4];boolean[] upgraded=new boolean[4];readAnimals(first.feature(),hits,upgraded);
        int[] hearts=first.feature().hearts();int[] types=first.feature().t();
        check(first.feature().roles().length==0,"opening cannot contain attacks");
        check(types.length==(mode==1?2:3),"opening monster count");
        for(int t:types)check(hits[t]==0,"opening collector must start at zero");
        check(hearts.length==types.length,"opening hearts length");
        for(int h:hearts)check(h==1||h==2,"opening heart value");
        check(Arrays.stream(types).distinct().count()==types.length,"duplicate monster type");
        if(round.buyType()==3)check(mode==2&&Arrays.stream(hearts).allMatch(h->h==1)&&!upgraded[1]&&!upgraded[2]&&!upgraded[3],"buy3 opening");
        if(round.buyType()==4)check(mode==3&&Arrays.stream(hearts).allMatch(h->h==2)&&upgraded[1]&&upgraded[2]&&upgraded[3],"buy4 opening");
        if(round.buyType()==5)check(mode==2,"buy5 opening");
        if(round.buyType()==5){int upgrades=0;for(int t:types)if(upgraded[t])upgrades++;check(upgrades<=2&&Arrays.stream(hearts).filter(h->h==2).count()==upgrades,"buy5 initial upgrade/heart count");}
        int[] firstLoc=locations(first.feature());
        for(int col=1;col<=3;col++){int n=0;for(int row=0;row<3;row++)if(firstLoc[col*3+row]>0)n++;check(n<=1,"opening Scatter reel cap");}
        int[] previousLoc=firstLoc;int turn=0;
        for(int c=0;c<15;c++)check((first.board()[c]==100)==(firstLoc[c]>0),"trigger Scatter locations");
        for(int i=1;i<round.steps().size();i++){
            var step=round.steps().get(i);var f=step.feature();
            check(HuntTrace.generated(f.roles()),"mixed generated/legacy hunting chain");
            var trace=HuntTrace.decode(f.roles());check(trace.spin()==turn&&trace.mode()==step.gameType()&&trace.buy()==round.buyType(),"trace header mismatch");
            for(var action:trace.actions())check(action.next()==step.nextType(),"action next state mismatch");
            check(Arrays.equals(types,f.t()),"monster identities changed");
            int[] startHits=new int[4];boolean[] startUp=new boolean[4];readAnimals(f,startHits,startUp);
            check(Arrays.equals(hits,startHits)&&Arrays.equals(upgraded,startUp),"collector continuity");
            int[] visible=locations(f),collision=visible.clone();Set<Integer> split=new LinkedHashSet<>(),w1=new LinkedHashSet<>(),w2=new LinkedHashSet<>();
            int[] trail=f.rbs();int treeCursor=1;
            if(step.gameType()!=4){
                for(int type:types){int before=cell(previousLoc,type),after=cell(visible,type);check(after>=3,"missing monster");
                    if(type==1&&trace.spin()%2==1)check(before==after,"ICE moves only every second spin");
                    else if(type<=2)check(Math.max(Math.abs(before/3-after/3),Math.abs(before%3-after%3))<=1,"monster moved more than one step");
                }
                int tree=cell(visible,3);check(tree<0?trail.length==0:trail.length>0&&trail[0]==tree,"initial tree position mismatch");
            }
            if(step.gameType()==4){
                check(trace.actions().size()==2,"reward has two weapons");hearts=restore(hearts,3);
                for(int c=3;c<12;c++)check(step.board()[c]==0,"reward 3x3 Wild");
                int[] rewardLoc=new int[15];rewardLoc[6]=3;rewardLoc[7]=2;rewardLoc[8]=1;
                check(Arrays.equals(visible,rewardLoc)&&trail.length==0,"reward monster layout");
                Set<Integer> rewardCuts=new LinkedHashSet<>(),rewardWilds=new LinkedHashSet<>();
                for(int w=0;w<2;w++){
                    var a=trace.actions().get(w);check(a.weapon()==w+1&&a.health()==w+1&&a.direction()==1&&a.blocked()==1&&a.row()<3,"reward weapon");
                    check(Arrays.equals(a.hearts(),hearts),"reward restored hearts");check(a.multiplier()==5,"reward global multiplier");
                    check(Arrays.equals(a.before(),rewardLoc)&&Arrays.equals(a.after(),rewardLoc),"reward attack locations");
                    int[] ah=new int[4];boolean[] au=new boolean[4];readAnimals(a.animals(),ah,au);check(Arrays.equals(ah,hits)&&Arrays.equals(au,upgraded),"reward collectors changed");
                    if(a.row()==1){rewardCuts.add(12);rewardCuts.add(13);rewardCuts.add(14);}
                    if(a.row()==0){check(a.wild1().length==rewardWilds.size()+1,"reward top must add one Wild");int c=a.wild1()[a.wild1().length-1];check(c>=12&&c<=14&&rewardWilds.add(c),"reward Wild placement");}
                    check(Arrays.equals(a.split(),array(rewardCuts))&&Arrays.equals(a.wild1(),array(rewardWilds))&&a.wild2().length==0,"reward effects mismatch");
                }
                for(int c=0;c<15;c++)check((step.board()[c]==0)==(c>=3&&c<12||rewardWilds.contains(c)),"reward Wild board mismatch");
                check(Arrays.equals(f.hearts(),hearts),"reward outer hearts");check(step.nextType()==3,"reward returns to shadows");Arrays.fill(hits,0);
            }else{
                int weaponCount=step.gameType()==3||(trace.spin()+1)%5==0?2:1;
                int weapon=1,remaining=1,direction=1;
                for(var a:trace.actions()){
                    check(a.weapon()==weapon&&a.direction()==direction&&a.health()==remaining&&a.row()<3,"weapon order/health");
                    check(Arrays.equals(a.before(),visible),"weapon starting locations");boolean hit=false;
                    for(int col=direction==1?4:0;col>=0&&col<5&&remaining>0;col+=direction==1?-1:1){
                        int cell=col*3+a.row(),t=collision[cell];if(t==0)continue;hit=true;collision[cell]=0;remaining=t==1?0:remaining-1;
                        if(t<=3){
                            if(remaining==0&&(step.gameType()==3||hits[t]<5))hits[t]++;
                            if(!upgraded[t]&&hits[t]>=5){upgraded[t]=true;for(int k=0;k<hearts.length;k++)if(hearts[k]<2){hearts[k]=2;break;}}
                            if(t==2)for(int c=direction==1?4:0;direction==1?c>col:c<col;c+=direction==1?-1:1)for(int rr=0;rr<3;rr++)if(upgraded[2]||rr==a.row())split.add(c*3+rr);
                            if(t==3){
                                (weapon==1?w1:w2).add(cell);check(treeCursor<trail.length,"missing tree jump");int next=trail[treeCursor++];
                                check(next>=3&&next<12&&next!=cell&&collision[next]==0&&visible[next]==0&&!w1.contains(next)&&!w2.contains(next),"tree jump destination");
                                visible[cell]=0;visible[next]=3;collision[next]=3;
                            }
                        }else{check(t==4&&upgraded[3],"trap without upgraded tree");if(remaining==0&&(step.gameType()==3||hits[3]<5))hits[3]++;(weapon==1?w1:w2).add(cell);visible[cell]=0;}
                    }
                    if(direction==2&&remaining>0)for(int k=hearts.length-1;k>=0;k--)if(hearts[k]>0){hearts[k]--;break;}
                    check(a.blocked()==(hit?1:0),"incorrect weapon stop");
                    check(Arrays.equals(a.after(),visible)&&Arrays.equals(a.hearts(),hearts),"attack state mismatch");
                    int[] ah=new int[4];boolean[] au=new boolean[4];readAnimals(a.animals(),ah,au);check(Arrays.equals(ah,hits)&&Arrays.equals(au,upgraded),"hit/upgrade mismatch");
                    check(Arrays.equals(a.split(),array(split))&&Arrays.equals(a.wild1(),array(w1))&&Arrays.equals(a.wild2(),array(w2)),"weapon special effects mismatch");
                    int m=Arrays.stream(types).anyMatch(t->t==1)?upgraded[1]?5:Math.min(5,hits[1]+1):1;check(a.multiplier()==m,"ICE multiplier mismatch");
                    if(remaining==0||direction==2){weapon++;remaining=weapon;direction=1;}else direction=2;
                }
                check(weapon==weaponCount+1&&direction==1,"missing or extra weapon");
                check(treeCursor==Math.max(1,trail.length),"unused tree jump");
                check(Arrays.equals(f.hearts(),hearts),"outer heart state mismatch");
                Set<Integer> zero=new HashSet<>();for(int c=0;c<15;c++)if(visible[c]>0&&visible[c]<4)zero.add(c);for(int c:trail)zero.add(c);zero.addAll(w1);zero.addAll(w2);
                for(int c=0;c<15;c++)check((step.board()[c]==0)==zero.contains(c),"Wild overlay mismatch");
                int next=Arrays.stream(hearts).sum()==0?0:step.gameType();
                if(next==2&&upgraded[1]&&upgraded[2]&&upgraded[3])next=3;
                if(next==3&&hits[1]+hits[2]+hits[3]>=20)next=4;
                check(next==step.nextType(),"feature transition mismatch");
                if(step.gameType()==2&&next==3){Arrays.fill(hits,0);hearts=restore(hearts,3);}
            }
            turn=step.gameType()==4||step.gameType()==2&&step.nextType()==3?0:turn+1;
            previousLoc=trace.actions().get(trace.actions().size()-1).after();
            check(Arrays.stream(step.board()).filter(x->x==0).count()<=9,"observed Wild cap exceeded");
            check(f.rbs().length<=3,"observed jump cap exceeded");
            for(int h:hits)check(h<=11,"observed collector cap exceeded");
        }
        check(Arrays.stream(hearts).sum()==0,"feature terminated with live hearts");
    }
    private static int cell(int[] loc,int type){for(int i=0;i<loc.length;i++)if(loc[i]==type)return i;return -1;}
    private static int[] restore(int[] a,int n){int[]b=Arrays.copyOf(a,n);for(int i=0;i<n;i++)if(b[i]==0)b[i]=1;return b;}
    private static void readAnimals(GameRuleCore.FeatureFacts f,int[]h,boolean[]u){int[]t=f.t(),bl=f.bl(),iu=f.iu();for(int i=0;i<t.length;i++){check(t[i]>=1&&t[i]<=3,"invalid animal type");h[t[i]]=bl[i];u[t[i]]=iu[i]==1;}}
    private static void readAnimals(int[] a,int[]h,boolean[]u){check(a.length%3==0,"invalid animals");for(int i=0;i<a.length;i+=3){check(a[i]>=1&&a[i]<=3,"invalid animal type");h[a[i]]=a[i+1];u[a[i]]=a[i+2]==1;}}
    private static int[] locations(GameRuleCore.FeatureFacts f){int[]a=new int[15],c=f.locCell(),t=f.locId();for(int i=0;i<c.length;i++){check(c[i]>=3&&c[i]<12&&a[c[i]]==0&&t[i]>=1&&t[i]<=4,"invalid battle location");a[c[i]]=t[i];}return a;}
    private static int[] array(Collection<Integer> c){return c.stream().mapToInt(Integer::intValue).toArray();}
    private static void check(boolean b,String message){if(!b)throw new IllegalArgumentException(message);}
}
