package com.cpgame.coinmastergo.service;

import com.cpgame.coinmastergo.core.CardMaterialState;
import com.cpgame.coinmastergo.model.PersistentState;
import com.cpgame.coinmastergo.model.PlayerSession;
import com.cpgame.coinmastergo.model.SpinStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StateStoreCardMaterialRestoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void reloadDecodesExplicitSilverStateWithoutAddingAProviderWireField() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path stateFile = temporaryDirectory.resolve("state.json");

        SpinStep step = new SpinStep();
        step.rskl = new ArrayList<>(List.of(
                "H1", "H2", "H3", "H4", "H5",
                "H1", "H2", "H3", "H4", "H5",
                "H1", "H2", "H3", "H4", "H5",
                "H1", "H2", "H3", "H4", "H5",
                "H1", "H2", "H3", "H4", "H5"));
        step.gfl = new ArrayList<>(List.of(10, 22, 34));
        step.silverCardCoordinates = new ArrayList<>(
                CardMaterialState.decodeSilverCoordinates(step.rskl, step.gfl));

        PlayerSession session = new PlayerSession();
        session.token = "restart-card-state";
        session.lastStep = step;
        PersistentState state = new PersistentState();
        state.sessionsByToken.put(session.token, session);
        mapper.writeValue(stateFile.toFile(), state);
        String persistedJson = Files.readString(stateFile);
        assertFalse(persistedJson.contains("silverCardCoordinates"),
                "the internal state must not leak into provider-compatible JSON");
        assertFalse(persistedJson.contains("\"sfl\""),
                "the captured provider contract has no sfl field");

        GameProperties properties = new GameProperties();
        properties.setStateFile(stateFile);
        StateStore store = new StateStore(mapper, properties);
        store.load();

        SpinStep restored = store.read(saved -> saved.sessionsByToken.get(session.token).lastStep);
        assertEquals(CardMaterialState.decodeSilverCoordinates(restored.rskl, restored.gfl),
                restored.silverCardCoordinates,
                "restart must reconstruct Silver as an explicit internal state");
    }
}
