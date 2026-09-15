package com.cpgame.coinmastergo;

import com.cpgame.coinmastergo.core.RoundValidator;
import com.cpgame.coinmastergo.model.SpinStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 只复核本次补采（scene 1471 以后）的真实相邻 Step 与紧邻 History。 */
class TargetedCapturedRoundRegressionTest {
    private static final int BASELINE_LAST_SCENE = 1470;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path captureRoot = Path.of("").toAbsolutePath().normalize()
            .getParent().getParent().resolve("captures/1407-mac-Coin-Master-GO");

    @Test
    void everyNewCompleteRoundHasPhysicalContinuityMultiplierBalanceAndAccumulators() throws Exception {
        Map<Integer, SpinStep> steps = new HashMap<>();
        for (String line : Files.readAllLines(captureRoot.resolve("step-index.jsonl"))) {
            JsonNode row = mapper.readTree(line);
            int scene = row.path("sceneIndex").asInt();
            if (scene > BASELINE_LAST_SCENE) {
                steps.put(scene, mapper.treeToValue(row.path("response").path("data"), SpinStep.class));
            }
        }

        int checkedRounds = 0;
        int checkedTransitions = 0;
        for (String line : Files.readAllLines(captureRoot.resolve("round-index.jsonl"))) {
            JsonNode round = mapper.readTree(line);
            int start = round.path("startSceneIndex").asInt();
            int end = round.path("endSceneIndex").asInt();
            if (start <= BASELINE_LAST_SCENE || !"complete".equals(round.path("status").asText())) continue;

            List<SpinStep> sequence = java.util.stream.IntStream.rangeClosed(start, end)
                    .mapToObj(scene -> {
                        SpinStep step = steps.get(scene);
                        assertNotNull(step, "完整 Round 不得缺 scene " + scene);
                        return step;
                    }).toList();
            assertTrue(sequence.getFirst().ba.signum() > 0);
            BigDecimal postDebit = new BigDecimal(sequence.getFirst().pb);
            BigDecimal rwa = BigDecimal.ZERO;
            BigDecimal frwa = BigDecimal.ZERO;
            int deliveryStep = 0;
            boolean free = false;

            for (int index = 0; index < sequence.size(); index++) {
                SpinStep step = sequence.get(index);
                if (index > 0) assertEquals(0, step.ba.signum(), "只有付费起点允许扣款");
                if (index == 0) {
                    assertEquals(1, step.rpx);
                } else if (sequence.get(index - 1).ss == 1) {
                    free = true;
                    deliveryStep = 0;
                }
                List<Integer> multipliers = free ? List.of(2, 4, 6, 10) : List.of(1, 2, 3, 5);
                assertEquals(multipliers.get(Math.min(deliveryStep, multipliers.size() - 1)), step.rpx,
                        "rpx 必须按 Delivery 阶段推进");

                rwa = rwa.add(step.wa);
                if (step.gt == 2) frwa = frwa.add(step.wa);
                assertEquals(0, rwa.compareTo(step.rwa), "rwa 必须逐 Step 累计");
                assertEquals(0, frwa.compareTo(step.frwa), "frwa 只累计免费 Step");

                BigDecimal expectedPb = index + 1 == sequence.size() ? postDebit.add(rwa) : postDebit;
                assertEquals(0, expectedPb.compareTo(new BigDecimal(step.pb)), "余额必须仅在完整 Round 末结算");

                if (index + 1 < sequence.size() && step.ss == 0) {
                    RoundValidator.validateCascadeTransition(step, sequence.get(index + 1));
                    checkedTransitions++;
                    deliveryStep++;
                }
            }
            SpinStep terminal = sequence.getLast();
            assertEquals(1, terminal.ss);
            assertTrue(terminal.fsn == 0 || (terminal.gt == 2 && terminal.nfsc == terminal.fsn));
            checkedRounds++;
        }
        Assumptions.assumeTrue(checkedRounds > 0,
                "本次隔离复刻明确保留 scene 1470 后未补采的证据缺口；不得伪造新增完整 Round");
        assertTrue(checkedTransitions > 0, "必须实际复核新增逐格相邻连消");
    }

    @Test
    void adjacentHistoryDetailsAreUniqueAndPreserveBaseThenFreeClassification() throws Exception {
        Path details = captureRoot.resolve("targeted-history-details-redacted.jsonl");
        Assumptions.assumeTrue(Files.isRegularFile(details),
                "本次隔离复刻明确保留 History 非严格 1:1 的证据缺口；不得伪造紧邻 detail");
        Set<String> transferIds = new HashSet<>();
        int freeDetails = 0;
        for (String line : Files.readAllLines(details)) {
            JsonNode record = mapper.readTree(line);
            assertTrue(transferIds.add(record.path("transferId").asText()), "History 必须按 transferId 去重");
            JsonNode data = record.path("response").path("data");
            assertTrue(data.path("bsl").isArray() && !data.path("bsl").isEmpty(), "每个付费 Round 必须有 bsl");
            assertTrue(data.path("fsl").isArray(), "fsl 必须保持数组合同");
            if (!data.path("fsl").isEmpty()) freeDetails++;
        }
        assertFalse(transferIds.isEmpty(), "必须取得本次 Spin 的紧邻 History");
        assertTrue(freeDetails > 0, "紧邻 History 必须包含本次免费完整 Round");
    }
}
