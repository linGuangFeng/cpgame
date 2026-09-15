package com.hd.cpgame.riocarnival.server.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class StateRepository {
    private final ObjectMapper mapper;
    private final Path file;
    public StateRepository(ObjectMapper mapper, @Value("${rio.state-directory:./data}") String directory) {
        this.mapper=mapper; this.file=Paths.get(directory).toAbsolutePath().normalize().resolve("sessions.json");
    }
    public synchronized StoredState load() {
        try {
            if (!Files.exists(file)) return new StoredState();
            return mapper.readValue(file.toFile(), StoredState.class);
        } catch (Exception e) { throw new IllegalStateException("无法读取本地会话状态: " + file, e); }
    }
    public synchronized void save(Collection<GameSession> sessions) {
        try {
            Files.createDirectories(file.getParent());
            StoredState state = new StoredState(); state.sessions.addAll(sessions);
            Path temp = file.resolveSibling(file.getFileName().toString()+".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { throw new IllegalStateException("无法保存本地会话状态: " + file, e); }
    }
}
