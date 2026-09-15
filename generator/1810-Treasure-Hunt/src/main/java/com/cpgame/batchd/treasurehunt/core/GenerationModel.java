package com.cpgame.batchd.treasurehunt.core;

import java.security.SecureRandom;

public final class GenerationModel {
    private static final int[][] POSITION={{724,564,600,624,532,564,216},{725,574,614,682,605,573,51},{730,580,610,605,641,547,111},{681,615,603,580,552,580,213},{870,592,628,560,567,566,41},{793,585,606,611,588,600,41},{647,587,613,625,602,624,126},{634,628,577,618,539,608,220},{709,608,622,607,601,623,54},{665,611,556,576,607,665,144}};
    private static final int[][] TRANSITION={{1552,961,890,919,930,956,305},{949,985,864,795,756,866,118},{930,827,1072,900,845,802,97},{874,845,838,1076,924,814,141},{885,836,819,817,888,814,168},{900,767,833,814,812,991,168},{364,159,113,143,147,143,4}};
    private static final int[] TREASURE_LENGTH={2,3,4,5,7,8,9}; private static final int[] TREASURE_LENGTH_WEIGHT={72,47,11,5,3,1,1};
    private GenerationModel(){}
    public static int symbol(SecureRandom random,int position,int previous){int[] p=POSITION[position];int[] weights=new int[7];for(int i=0;i<7;i++)weights[i]=Math.max(1,p[i]*TRANSITION[Math.max(1,Math.min(7,previous))-1][i]);return 1+weighted(random,weights);}
    public static int treasureLength(SecureRandom random){return TREASURE_LENGTH[weighted(random,TREASURE_LENGTH_WEIGHT)];}
    public static int weighted(SecureRandom random,int[] weights){long total=0;for(int w:weights)total+=Math.max(0,w);long pick=random.nextLong(total);for(int i=0;i<weights.length;i++){pick-=Math.max(0,weights[i]);if(pick<0)return i;}return weights.length-1;}

    public static int lossSymbol(SecureRandom random,int position,int previous,boolean[] forbidden){
        int[] weights=new int[7];
        for(int i=0;i<6;i++)if(!forbidden[i+1])weights[i]=Math.max(1,POSITION[position][i]*TRANSITION[Math.max(1,Math.min(7,previous))-1][i]);
        return 1+weighted(random,weights);
    }
}
