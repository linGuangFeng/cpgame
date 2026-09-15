package com.cpgame.christmasgift.api;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StateStore {
    record HistoryRecord(long oid, String mode, BigDecimal betGold, BigDecimal totalWin, BigDecimal endGold,
                         String createdAt, String dataJson) implements Serializable {}
    static final class State implements Serializable {
        private static final long serialVersionUID = 38L;
        BigDecimal balance = new BigDecimal("10000.00");
        long nextOid = 2096000000000000000L;
        String lastDataJson;
        final LinkedHashMap<String,String> idempotencyResponses = new LinkedHashMap<>();
        final ArrayDeque<CacheRepository.Pool> qaSelections = new ArrayDeque<>();
        final ArrayList<HistoryRecord> history = new ArrayList<>();
    }
    private final Path file;
    private final State state;
    StateStore(Path file) throws Exception { this.file=file; this.state=load(); }
    synchronized State state() { return state; }
    synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (ObjectOutputStream output = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            output.writeObject(state);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    synchronized void rememberIdempotent(String requestId, String response) {
        if (requestId == null || requestId.isBlank()) return;
        state.idempotencyResponses.put(requestId, response);
        while (state.idempotencyResponses.size() > 500) {
            state.idempotencyResponses.remove(state.idempotencyResponses.keySet().iterator().next());
        }
    }
    synchronized String idempotent(String requestId) { return requestId == null ? null : state.idempotencyResponses.get(requestId); }
    synchronized void addHistory(HistoryRecord record) {
        state.history.add(0,record);
        while(state.history.size()>500) state.history.remove(state.history.size()-1);
    }
    synchronized List<HistoryRecord> history() { return List.copyOf(state.history); }
    synchronized HistoryRecord history(long oid) { return state.history.stream().filter(item->item.oid()==oid).findFirst().orElse(null); }
    synchronized void enqueue(CacheRepository.Pool pool, int count) { for(int i=0;i<count;i++) state.qaSelections.addLast(pool); }
    synchronized void clearSelections() { state.qaSelections.clear(); }
    synchronized CacheRepository.Pool pollSelection() { return state.qaSelections.pollFirst(); }
    synchronized int queuedSelections() { return state.qaSelections.size(); }

    private State load() throws Exception {
        if (!Files.exists(file)) return new State();
        try (ObjectInputStream input = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            Object value = input.readObject();
            if (!(value instanceof State restored)) throw new IOException("invalid persisted state type");
            return restored;
        }
    }
    static HistoryRecord history(long oid,String mode,BigDecimal betGold,BigDecimal totalWin,BigDecimal endGold,String dataJson) {
        return new HistoryRecord(oid,mode,betGold,totalWin,endGold,Instant.now().toString(),dataJson);
    }
}
