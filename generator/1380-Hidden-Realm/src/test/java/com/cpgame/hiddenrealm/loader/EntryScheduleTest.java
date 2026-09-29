package com.cpgame.hiddenrealm.loader;
public final class EntryScheduleTest {
 public static void main(String[] args) {
  EntrySchedule s=new EntrySchedule(100000000,100000000);
  for(int i=0;i<1000;i++)if(s.next()!=0)throw new AssertionError("early switch");
  if(s.next()!=1)throw new AssertionError("rejections starved special pool");
  s=new EntrySchedule(0,2,1);int[] got=new int[3];
  for(int p;(p=s.next())>=0;){s.accepted(p);got[p]++;}
  if(got[0]!=0||got[1]!=2||got[2]!=1)throw new AssertionError("quota");
  s=new EntrySchedule(1,100000000);
  if(s.next()!=0)throw new AssertionError();s.accepted(0);
  if(s.next()!=1)throw new AssertionError("finished pool not skipped");
  System.out.println("PASS rejection fairness, huge quota, disabled/completed entry, exact quota");
 }
}
