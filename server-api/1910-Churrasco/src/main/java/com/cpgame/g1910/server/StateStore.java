package com.cpgame.g1910.server;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small in-memory demo wallet/history; gameplay facts still come exclusively from Redis. */
final class StateStore {
    private BigDecimal balance=new BigDecimal("10000.00");private final List<Map<String,Object>>history=new ArrayList<>();
    synchronized BigDecimal balance(){return balance;}
    synchronized void settle(BigDecimal cost,BigDecimal award,Map<String,Object>entry){balance=balance.subtract(cost).add(award);history.add(0,entry);if(history.size()>100)history.remove(history.size()-1);}
    synchronized List<Map<String,Object>>history(){return List.copyOf(history);}
}
