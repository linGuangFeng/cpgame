package com.cpgame.coinmastergo.service;

import com.cpgame.coinmastergo.core.CardMaterialState;
import com.cpgame.coinmastergo.model.HistoryRecord;
import com.cpgame.coinmastergo.model.PersistentState;
import com.cpgame.coinmastergo.model.PlayerSession;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

@Component
public class StateStore {
    private final ObjectMapper mapper;
    private final Path stateFile;
    private PersistentState state;

    public StateStore(ObjectMapper mapper, GameProperties properties) {
        this.mapper = mapper;
        this.stateFile = properties.getStateFile().toAbsolutePath().normalize();
    }

    @PostConstruct
    void load() {
        try {
            state = Files.isRegularFile(stateFile)
                    ? mapper.readValue(stateFile.toFile(), PersistentState.class)
                    : new PersistentState();
            restoreExplicitCardMaterialState(state);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load durable game state " + stateFile, e);
        }
    }

    /**
     * Captured responses carry Gold in gfl and imply the ordinary/internal Silver
     * partition as the rest of the eligible cards. Recreate that internal state at
     * the persistence boundary without changing the provider wire contract.
     */
    private static void restoreExplicitCardMaterialState(PersistentState persisted) {
        for (PlayerSession session : persisted.sessionsByToken.values()) {
            restoreStep(session.lastStep);
            session.idempotentSpinResponses.values().forEach(StateStore::restoreStep);
            restoreRound(session.activeRound);
            for (HistoryRecord history : session.history) restoreRound(history.round);
        }
    }

    private static void restoreRound(RoundPlan round) {
        if (round == null) return;
        round.deliveries.forEach(delivery -> delivery.steps.forEach(StateStore::restoreStep));
    }

    private static void restoreStep(SpinStep step) {
        if (step == null || step.rskl == null || step.rskl.isEmpty()) return;
        step.silverCardCoordinates = new java.util.ArrayList<>(
                CardMaterialState.decodeSilverCoordinates(step.rskl, step.gfl));
    }

    public synchronized <T> T read(Function<PersistentState, T> operation) {
        return operation.apply(state);
    }

    public synchronized <T> T transaction(Function<PersistentState, T> operation) {
        T result = operation.apply(state);
        persist();
        return result;
    }

    private void persist() {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist durable game state " + stateFile, e);
        }
    }
}
