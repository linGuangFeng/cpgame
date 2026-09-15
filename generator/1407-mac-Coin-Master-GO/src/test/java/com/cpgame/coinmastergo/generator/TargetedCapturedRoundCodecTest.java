package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 用本次新增真实 Round 事实验证生成/反推/Redis Codec 的双向一致性。 */
class TargetedCapturedRoundCodecTest {
    private static final int BASELINE_LAST_SCENE = 1470;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path captureRoot = Path.of("").toAbsolutePath().normalize()
            .getParent().getParent().resolve("captures/1407-mac-Coin-Master-GO");

    @Test
    void everyNewCompleteRoundFactRoundTripsThroughTheFormalCodecAndResultUtil() throws Exception {
        Map<Integer, SpinStep> steps = new HashMap<>();
        for (String line : Files.readAllLines(captureRoot.resolve("step-index.jsonl"))) {
            JsonNode row = mapper.readTree(line);
            int scene = row.path("sceneIndex").asInt();
            if (scene > BASELINE_LAST_SCENE) {
                steps.put(scene, mapper.treeToValue(row.path("response").path("data"), SpinStep.class));
            }
        }

        MinimalFactCodec codec = new MinimalFactCodec();
        int checked = 0;
        int free = 0;
        int retrigger = 0;
        for (String line : Files.readAllLines(captureRoot.resolve("round-index.jsonl"))) {
            JsonNode index = mapper.readTree(line);
            int start = index.path("startSceneIndex").asInt();
            int end = index.path("endSceneIndex").asInt();
            if (start <= BASELINE_LAST_SCENE || !"complete".equals(index.path("status").asText())) continue;

            RoundPlan capturedFacts = new RoundPlan();
            capturedFacts.roundKey = "capture-" + start;
            capturedFacts.transferId = "capture-" + start;
            capturedFacts.deliveries = new ArrayList<>();
            RoundDelivery delivery = new RoundDelivery("BASE", new ArrayList<>());
            capturedFacts.deliveries.add(delivery);
            int previousFsn = 0;
            boolean isRetrigger = false;
            for (int scene = start; scene <= end; scene++) {
                SpinStep step = steps.get(scene);
                assertNotNull(step, "Codec 输入完整 Round 不得缺 scene " + scene);
                if (step.gt == 2 && (delivery.steps.isEmpty() || delivery.steps.getLast().ss == 1)) {
                    delivery = new RoundDelivery("FREE", new ArrayList<>());
                    capturedFacts.deliveries.add(delivery);
                }
                delivery.steps.add(step);
                if (step.fsn > 0 && previousFsn == 0) previousFsn = step.fsn;
                else if (step.fsn > previousFsn) {
                    isRetrigger = true;
                    previousFsn = step.fsn;
                }
            }

            String member = codec.encodeFull(capturedFacts);
            RoundPlan rebuilt = codec.rebuild(member);
            assertEquals(codec.extract(capturedFacts), codec.extract(rebuilt),
                    "最小事实编码和 GameRuleCore 反推必须双向一致");
            checked++;
            if (rebuilt.deliveries.size() > 1) free++;
            if (isRetrigger) retrigger++;
        }
        Assumptions.assumeTrue(checked > 0,
                "本次隔离复刻明确保留 scene 1470 后未补采的证据缺口；不得伪造新增完整 Round");
        assertTrue(free > 0, "必须覆盖本次新增免费 Round");
        assertTrue(retrigger > 0, "必须覆盖本次新增重触发 Round");
    }
}
