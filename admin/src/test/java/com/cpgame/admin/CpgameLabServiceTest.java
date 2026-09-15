package com.cpgame.admin;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpgameLabServiceTest {
    @Test
    void firstNonBlankLineKeepsTheFirstHttpAddress() {
        assertEquals(
            "http://127.0.0.1:51090/v2/1090/index.html?gid=1090",
            CpgameLabService.firstNonBlankLine(
                "http://127.0.0.1:51090/v2/1090/index.html?gid=1090\n"
                    + "http://192.168.24.37:51090/v2/1090/index.html?gid=1090\n"));
        assertNull(CpgameLabService.firstNonBlankLine(" \n \n"));
        assertNull(CpgameLabService.firstNonBlankLine(null));
    }

    @Test
    void listGamesReadsSingleLineDemoUrlAsPlayEntry() throws Exception {
        Path root = Files.createTempDirectory("cpgame-1090-lab");
        Path publish = root.resolve("publish").resolve("1090-sharpshooter");
        Path server = root.resolve("server-api").resolve("1090-sharpshooter");
        Files.createDirectories(publish);
        Files.createDirectories(server);
        Files.writeString(publish.resolve("index.html"), "<html></html>");
        Files.writeString(publish.resolve("publish-manifest.json"),
            "{\"gameId\":1090,\"name\":\"Sharpshooter\"}");
        Files.writeString(server.resolve("demo-url.txt"),
            "http://127.0.0.1:51090/v2/1090/index.html?gid=1090&language=en&token=demo\n");
        CpgameLabService lab = new CpgameLabService(
            new AdminSettings(root, root.resolve("runtime"), "127.0.0.1", 8000),
            new ObjectMapper(), uri -> true);
        List<CpgameLabService.GameCard> games = lab.listGames();
        assertEquals(1, games.size());
        CpgameLabService.GameCard game = games.get(0);
        assertEquals("1090-sharpshooter", game.directoryName());
        assertNotNull(game.demoUrl());
        assertTrue(game.publishReady());
        assertTrue(game.demoUrl().startsWith("http://127.0.0.1:51090/"));
    }

    @Test
    void listGamesReadsLanguagesFromCurrentStatusLanguagesField() throws Exception {
        Path root = Files.createTempDirectory("cpgame-60-lab");
        Path publish = root.resolve("publish").resolve("60-Crazy-Birds");
        Path reports = root.resolve("reports").resolve("60-Crazy-Birds");
        Files.createDirectories(publish);
        Files.createDirectories(reports);
        Files.writeString(publish.resolve("index.html"), "<html></html>");
        Files.writeString(publish.resolve("publish-manifest.json"),
            "{\"gameId\":60,\"name\":\"Crazy Birds\"}");
        Files.writeString(reports.resolve("current-status.json"),
            "{\"languages\":[\"en\",\"pt-br\",\"es\",\"th\",\"vi\",\"id\",\"bn\",\"ko\",\"fr\",\"tr\"]}");
        CpgameLabService lab = new CpgameLabService(
            new AdminSettings(root, root.resolve("runtime"), "127.0.0.1", 8000),
            new ObjectMapper(), uri -> true);
        List<CpgameLabService.GameCard> games = lab.listGames();
        assertEquals(1, games.size());
        assertEquals(
            List.of("en", "pt-br", "es", "th", "vi", "id", "bn", "ko", "fr", "tr"),
            games.get(0).languages());
    }

    @Test
    void listGamesReadsLanguageCodesFromInventoryObjects() throws Exception {
        Path root = Files.createTempDirectory("cpgame-60-inventory-lab");
        Path publish = root.resolve("publish").resolve("60-Crazy-Birds");
        Path reports = root.resolve("reports").resolve("60-Crazy-Birds");
        Files.createDirectories(publish);
        Files.createDirectories(reports);
        Files.writeString(publish.resolve("index.html"), "<html></html>");
        Files.writeString(publish.resolve("publish-manifest.json"),
            "{\"gameId\":60,\"name\":\"Crazy Birds\"}");
        Files.writeString(reports.resolve("language-inventory.json"),
            "{\"languages\":[{\"code\":\"en\"},{\"code\":\"pt\",\"archive\":\"pt-br\"},{\"code\":\"es\"}]}");
        CpgameLabService lab = new CpgameLabService(
            new AdminSettings(root, root.resolve("runtime"), "127.0.0.1", 8000),
            new ObjectMapper(), uri -> true);
        List<CpgameLabService.GameCard> games = lab.listGames();
        assertEquals(1, games.size());
        assertEquals(List.of("en", "pt", "es"), games.get(0).languages());
    }

    @Test
    void listGamesSortsByNumericGameIdAscending() throws Exception {
        Path root = Files.createTempDirectory("cpgame-sort-lab");
        writePublishedGame(root, "1407-Coin-Master-GO", "1407", "Coin Master GO");
        writePublishedGame(root, "8-Jurassic-Jungle", "8", "Jurassic Jungle");
        writePublishedGame(root, "60-Crazy-Birds", "60", "Crazy Birds");
        CpgameLabService lab = new CpgameLabService(
            new AdminSettings(root, root.resolve("runtime"), "127.0.0.1", 8000),
            new ObjectMapper(), uri -> true);
        List<CpgameLabService.GameCard> games = lab.listGames();
        assertEquals(
            List.of("8-Jurassic-Jungle", "60-Crazy-Birds", "1407-Coin-Master-GO"),
            games.stream().map(CpgameLabService.GameCard::directoryName).toList());
    }

    @Test
    void numericGameIdPutsBlankAndNonNumericIdsLast() {
        assertEquals(8L, CpgameLabService.numericGameId("8"));
        assertEquals(1407L, CpgameLabService.numericGameId("1407"));
        assertEquals(Long.MAX_VALUE, CpgameLabService.numericGameId("abc"));
        assertEquals(Long.MAX_VALUE, CpgameLabService.numericGameId(""));
        assertEquals(Long.MAX_VALUE, CpgameLabService.numericGameId(null));
    }

    private static void writePublishedGame(Path root, String directory, String gameId, String name) throws Exception {
        Path publish = root.resolve("publish").resolve(directory);
        Files.createDirectories(publish);
        Files.writeString(publish.resolve("index.html"), "<html></html>");
        Files.writeString(publish.resolve("publish-manifest.json"),
            "{\"gameId\":" + gameId + ",\"name\":\"" + name + "\"}");
    }
}
