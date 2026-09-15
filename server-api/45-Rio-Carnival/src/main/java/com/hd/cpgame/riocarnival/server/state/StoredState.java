package com.hd.cpgame.riocarnival.server.state;

import java.util.ArrayList;
import java.util.List;

public final class StoredState {
    public int schemaVersion = 1;
    public List<GameSession> sessions = new ArrayList<GameSession>();
}
