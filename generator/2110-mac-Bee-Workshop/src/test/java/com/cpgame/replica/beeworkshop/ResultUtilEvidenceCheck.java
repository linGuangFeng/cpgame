package com.cpgame.replica.beeworkshop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/** Recalculate original capture win_arr with independent ResultUtil. */
public final class ResultUtilEvidenceCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "fixtures/2110-Bee-Workshop/spin-origin-20260908/rounds" : args[0]);
        ObjectMapper json = new ObjectMapper();
        ResultUtil util = new ResultUtil(new GameRuleCore());
        int checked = 0, positive = 0, mystery = 0, freeTrigger = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.getFileName().toString().equals("step-001.response.json"))::iterator) {
                JsonNode rootNode = json.readTree(path.toFile());
                JsonNode d = rootNode.has("body") ? rootNode.path("body").path("data") : rootNode.path("data");
                JsonNode props = d.path("props");
                if (!props.has("prop") || props.path("prop").size() != 15) continue;
                int[] board = json.treeToValue(props.path("prop"), int[].class);
                long expected = 0;
                for (JsonNode win : props.path("win_arr")) expected += win.path("odd").asLong();
                long actual = util.independentPayoutUnits(board);
                if (expected != actual) throw new IllegalStateException("original payout mismatch: " + path + " expected=" + expected + " actual=" + actual);
                if (expected > 0) positive++;
                if (d.path("spe_pos").isArray() && d.path("spe_pos").size() > 0) mystery++;
                if (d.path("frees").path("st").asInt() > 0) freeTrigger++;
                checked++;
            }
        }
        if (checked < 300) throw new IllegalStateException("too few original responses: " + checked);
        System.out.println("{\"test\":\"ResultUtil origin evidence\",\"status\":\"PASS\",\"responses\":" + checked
                + ",\"payingResponses\":" + positive + ",\"mystery\":" + mystery + ",\"freeTrigger\":" + freeTrigger + "}");
    }
}
