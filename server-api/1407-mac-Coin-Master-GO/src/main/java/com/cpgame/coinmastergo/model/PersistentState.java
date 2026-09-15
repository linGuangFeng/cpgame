package com.cpgame.coinmastergo.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class PersistentState {
    public int schemaVersion = 1;
    public Map<String, PlayerSession> sessionsByToken = new LinkedHashMap<>();
    public Map<String, String> authTokenByLaunchToken = new LinkedHashMap<>();
    public PersistentState() { }
}
