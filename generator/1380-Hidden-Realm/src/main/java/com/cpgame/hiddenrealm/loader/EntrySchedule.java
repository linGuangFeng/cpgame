package com.cpgame.hiddenrealm.loader;

import java.util.Arrays;

/** Rotate after at most 1000 attempts, including rejected candidates (1809 contract). */
final class EntrySchedule {
    private final int[] remaining;
    private int entry, attemptsInEntry;
    EntrySchedule(int... targets) {
        remaining=targets.clone();
        if(Arrays.stream(remaining).anyMatch(n->n<0))throw new IllegalArgumentException("negative target");
    }
    int next() {
        if(remaining.length==0)return -1;
        if(attemptsInEntry>=1000){entry=(entry+1)%remaining.length;attemptsInEntry=0;}
        for(int skipped=0;skipped<remaining.length;skipped++) {
            if(remaining[entry]>0){attemptsInEntry++;return entry;}
            entry=(entry+1)%remaining.length;attemptsInEntry=0;
        }
        return -1;
    }
    void accepted(int phase) {
        if(phase<0||phase>=remaining.length||remaining[phase]<=0)throw new IllegalStateException("completed entry");
        remaining[phase]--;
    }
}
