package com.cpgame.luckywheel.api;

import java.io.*;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PersistentSessionRepository {
    private final Path stateFile;
    private Store store;
    public PersistentSessionRepository(Path stateDirectory) throws IOException {
        Files.createDirectories(stateDirectory);
        stateFile = stateDirectory.resolve("lucky-wheel-sessions.bin");
        store = read();
    }
    public synchronized SessionState byLaunchKey(String key) { return store.byLaunchKey.get(key); }
    public synchronized SessionState byToken(String token) { return store.byToken.get(token); }
    public synchronized void add(SessionState session) throws IOException {
        store.byLaunchKey.put(session.launchKey(), session);
        store.byToken.put(session.token(), session);
        save();
    }
    public synchronized void save() throws IOException {
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(temporary))) { output.writeObject(store); }
        try { Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING); }
    }
    private Store read() {
        if (!Files.isRegularFile(stateFile)) return new Store();
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(stateFile))) {
            return (Store) input.readObject();
        } catch (Exception invalidState) {
            throw new IllegalStateException("持久化会话无法读取: " + stateFile, invalidState);
        }
    }
    private static final class Store implements Serializable {
        private final Map<String, SessionState> byLaunchKey = new LinkedHashMap<>();
        private final Map<String, SessionState> byToken = new LinkedHashMap<>();
    }
}
