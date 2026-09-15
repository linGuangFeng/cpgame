package com.cpgame.clubgoddess.api.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final ObjectMapper mapper;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> launches = new ConcurrentHashMap<>();
    @Value("${game.state-file:./data/session-store.json}") private String stateFile;

    public SessionRepository(ObjectMapper mapper) { this.mapper = mapper; }

    @PostConstruct void load() {
        try {
            Path path = path();
            if (Files.exists(path)) {
                StoreDocument doc = mapper.readValue(path.toFile(), StoreDocument.class);
                if (doc.sessions != null) sessions.putAll(doc.sessions);
                if (doc.launches != null) launches.putAll(doc.launches);
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot load session store", e); }
    }

    public SessionState get(String token) {
        SessionState state = sessions.get(token);
        if (state == null) throw new IllegalArgumentException("invalid session token");
        return state;
    }
    public SessionState find(String token) { return sessions.get(token); }
    public String findByLaunchHash(String hash) { return launches.get(hash); }

    public synchronized void put(String launchHash, SessionState state) {
        sessions.put(state.token, state); launches.put(launchHash, state.token); save();
    }
    public synchronized void save() {
        try {
            Path target = path(); Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            StoreDocument doc = new StoreDocument();
            doc.sessions = new LinkedHashMap<>(sessions); doc.launches = new LinkedHashMap<>(launches);
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), doc);
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception ignored) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { throw new IllegalStateException("Cannot persist session store", e); }
    }
    private Path path() { return Path.of(stateFile).toAbsolutePath().normalize(); }

    public static class StoreDocument {
        public Map<String, SessionState> sessions = new LinkedHashMap<>();
        public Map<String, String> launches = new LinkedHashMap<>();
    }
}
