package com.cpgame.coinmastergo.core;

import com.cpgame.coinmastergo.model.RoundDelivery;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.model.SpinStep;
import com.cpgame.coinmastergo.model.WinMatch;
import com.cpgame.coinmastergo.service.GameProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Component
public class GameRuleCore {
    private final Random random;
    private final ZeroLossSupport<List<String>> losses;
    private final RoundValidator validator = new RoundValidator();
    private SymbolTable openingTable;
    private SymbolTable subsequentTable;
    private int runtimeSilverCardWeight;
    private int runtimeGoldCardWeight;
    private int runtimeCardMaterialTotal;

    public GameRuleCore(GameProperties properties) {
        this.random = properties.getDemoSeed() == null ? new SecureRandom() : new Random(properties.getDemoSeed());
        installRuntimeSymbolWeights(GameRules.EVIDENCE_START_SYMBOL_WEIGHTS);
        configureCardMaterialWeights(GameRules.EVIDENCE_SILVER_CARD_WEIGHT, GameRules.EVIDENCE_GOLD_CARD_WEIGHT);
        losses=new ZeroLossSupport<>(this::lossBoardCandidate,GameRuleCore::isIndependentLossBoard,b->new ArrayList<>(b));
    }

    public String rulesVersion() { return GameRules.RULES_VERSION; }

    public String rulesHash() { return GameRules.RULES_HASH; }

    /**
     * Formal runtime entry point used by the playable demo. It generates fresh boards with the
     * current rule evaluator and pre-builds the whole paid Round. It never chooses a canned
     * scenario and never reads captures, fixtures or historical responses.
     */
    public synchronized RoundPlan generateRuntimeRound(String roundKey, String transferId,
                                                        int betLevel, BigDecimal betSize,
                                                        BigDecimal postDebitBalance, long createdAt) {
        for (int attempt = 0; attempt < 2_000; attempt++) {
            try {
                RoundPlan round = runtimeRoundShell(roundKey, transferId, betLevel, betSize,
                        postDebitBalance, createdAt);
                RuntimeAccumulator sums = new RuntimeAccumulator();
                List<String> paidBoard = runtimeRandomBoard(true);
                int paidAward = runtimeAppendDelivery(round, paidBoard, 1, round.betAmount, 0, 0, sums);
                // Paid Mary is only the opening Step: 3+ playable SC and no ways win (43/43 captures).
                if (paidAward > 0) {
                    var paid = round.deliveries.getFirst();
                    if (paid.steps.size() != 1 || paid.steps.getFirst().wa.signum() != 0) {
                        throw new RuntimeCandidateRejected();
                    }
                }
                int freeSpins = paidAward;
                for (int freeIndex = 1; freeIndex <= freeSpins; freeIndex++) {
                    List<String> freeBoard = runtimeRandomBoard(false);
                    int added = runtimeAppendDelivery(round, freeBoard, 2, BigDecimal.ZERO,
                            freeSpins, freeIndex, sums);
                    if (freeSpins + added > GameRules.CAPTURED_MAX_FSN) throw new RuntimeCandidateRejected();
                    freeSpins += added;
                }
                round.totalWin = CoinMasterResultUtil.money(sums.total);
                SpinStep last = round.deliveries.getLast().steps.getLast();
                last.pb = round.postDebitBalance.add(round.totalWin).setScale(2).toPlainString();
                round.scenario = runtimeScenario(round);
                validator.validate(round);
                return round;
            } catch (RuntimeCandidateRejected ignored) {
                // Reject the complete candidate. Runtime never truncates a Round or inserts a fixture.
            }
        }
        throw new IllegalStateException("unable to generate a verified complete runtime Round");
    }

    private RoundPlan runtimeRoundShell(String roundKey, String transferId, int betLevel,
                                        BigDecimal betSize, BigDecimal postDebitBalance,
                                        long createdAt) {
        RoundPlan round = new RoundPlan();
        round.roundKey = roundKey;
        round.transferId = transferId;
        round.paidBid = GameRules.GAME_PROTOCOL_ID + "-" + transferId;
        round.betLevel = betLevel;
        round.betSize = betSize;
        round.betAmount = GameRules.betAmount(betLevel, betSize);
        round.postDebitBalance = postDebitBalance;
        round.createdAt = createdAt;
        return round;
    }

    /**
     * Appends one Delivery. Scatter award is taken from the terminal board after
     * cascades finish, matching captured retriggers that complete 3+ SC on refill.
     */
    private int runtimeAppendDelivery(RoundPlan round, List<String> startingBoard, int gt,
                                       BigDecimal debit, int fsnBeforeAward, int nfsc,
                                       RuntimeAccumulator sums) {
        List<SpinStep> steps = new ArrayList<>();
        List<String> board = startingBoard;
        CardLayout cardLayout = runtimeInitialCardLayout(board);
        List<Integer> rpxValues = gt == 1 ? GameRules.BASE_RPX : GameRules.FREE_RPX;
        for (int cascade = 0; cascade < 10; cascade++) {
            int rpx = rpxValues.get(Math.min(cascade, rpxValues.size() - 1));
            CoinMasterResultUtil.Evaluation evaluation = CoinMasterResultUtil.evaluate(
                    board, round.betLevel, round.betSize, rpx);
            boolean continues = evaluation.totalWin().signum() > 0;
            int awardAtTerminal = continues ? 0 : runtimeFreeAward(board);
            int responseFsn = fsnBeforeAward + awardAtTerminal;
            int responseSmallGameType = gt == 2 ? 2 : (cascade == 0 ? 0 : 1);
            SpinStep current = step(round, board, gt, responseSmallGameType, rpx,
                    continues ? 0 : 1, responseFsn, nfsc,
                    cascade == 0 ? debit : BigDecimal.ZERO,
                    sums.total, sums.free, round.postDebitBalance);
            applyCardLayout(current, cardLayout);
            sums.total = sums.total.add(current.wa);
            if (gt == 2) sums.free = sums.free.add(current.wa);
            current.rwa = CoinMasterResultUtil.money(sums.total);
            current.frwa = CoinMasterResultUtil.money(sums.free);
            steps.add(current);
            if (!continues) {
                round.deliveries.add(new RoundDelivery(gt == 1 ? "BASE" : "FREE", steps));
                return awardAtTerminal;
            }
            CascadeProjection projection = cascadeBoard(current, runtimeRefills(current));
            board = projection.board();
            cardLayout = new CardLayout(projection.silverCoordinates(), projection.goldenCoordinates());
        }
        throw new RuntimeCandidateRejected();
    }

    private List<String> runtimeRandomBoard(boolean opening) {
        List<String> board = new ArrayList<>(GameRules.TRANSPORT_CELLS);
        for (int reel = 0; reel < GameRules.REELS; reel++) {
            boolean seenTrigger = false;
            for (int row = 0; row < GameRules.TRANSPORT_ROWS; row++) {
                SymbolTable table = opening && !seenTrigger ? openingTable : subsequentTable;
                String symbol = runtimeRandomSymbol(table);
                if ("SC".equals(symbol)) seenTrigger = true;
                board.add(symbol);
            }
        }
        return board;
    }

    private String runtimeRandomSymbol(SymbolTable table) {
        int draw = random.nextInt(table.total);
        for (int index = 0; index < table.cumulative.length; index++) {
            if (draw < table.cumulative[index]) return table.symbols.get(index);
        }
        throw new IllegalStateException("evidence symbol sampling exceeded cumulative weights");
    }

    private List<List<String>> runtimeRefills(SpinStep previous) {
        Set<Integer> winning = new HashSet<>();
        previous.wmkl.forEach(match -> match.forEach(winning::addAll));
        List<List<String>> refills = new ArrayList<>();
        for (int reel = 0; reel < GameRules.REELS; reel++) {
            List<String> reelRefills = new ArrayList<>();
            boolean nextFillsBuffer = true;
            for (int row = 0; row < GameRules.VISIBLE_ROWS; row++) {
                int raw = reel * 10 + row;
                if (winning.contains(raw) && !previous.gfl.contains(raw + 1)) {
                    // Visible-row refills may be SC (132 captured scatter-increase pairs). The first
                    // refill is the on-screen buffer, which freeAward does not count, so it is never SC.
                    reelRefills.add(nextFillsBuffer ? runtimeRandomNonScatterSymbol() : runtimeRandomSymbol(subsequentTable));
                    nextFillsBuffer = false;
                }
            }
            refills.add(reelRefills);
        }
        return refills;
    }

    private String runtimeRandomNonScatterSymbol() {
        String symbol;
        do {
            symbol = runtimeRandomSymbol(subsequentTable);
        } while ("SC".equals(symbol));
        return symbol;
    }

    private CardLayout runtimeInitialCardLayout(List<String> board) {
        List<Integer> silver = new ArrayList<>();
        List<Integer> gold = new ArrayList<>();
        for (int reel = 1; reel <= 3; reel++) {
            for (int transportRow = 0; transportRow < GameRules.TRANSPORT_ROWS; transportRow++) {
                String symbol = board.get(reel * GameRules.TRANSPORT_ROWS + transportRow);
                if (!GameRules.PAYING_SYMBOLS.contains(symbol)) continue;
                int coordinate = reel * 10 + transportRow;
                // Every eligible source-protocol card is assigned an explicit material state.
                // Silver is not an omitted/default branch in the rule model.
                if (random.nextInt(runtimeCardMaterialTotal) >= runtimeSilverCardWeight) {
                    gold.add(coordinate);
                } else {
                    silver.add(coordinate);
                }
            }
        }
        return new CardLayout(List.copyOf(silver), List.copyOf(gold));
    }

    private static void applyCardLayout(SpinStep step, CardLayout layout) {
        step.silverCardCoordinates = new ArrayList<>(layout.silverCoordinates());
        step.gfl = new ArrayList<>(layout.goldenCoordinates());
    }

    private static CardLayout decodedCardLayout(List<String> board, List<Integer> goldCoordinates) {
        return new CardLayout(CardMaterialState.decodeSilverCoordinates(board, goldCoordinates),
                List.copyOf(goldCoordinates));
    }

    /**
     * Installs an explicit local generation profile for the formal loader. The playable server
     * uses the exact 501-start evidence profile above. WILD is structurally transformation-only
     * and therefore must remain zero in every profile.
     */
    public synchronized void installRuntimeSymbolWeights(Map<String, Integer> weights) {
        SymbolTable table = symbolTable(weights);
        openingTable = table;
        subsequentTable = table;
    }

    /** 只改付费首局分布。连消/免费仍用 install 时的原倍数。 */
    public synchronized void configureSymbolWeights(Map<String, Integer> weights) {
        openingTable = symbolTable(weights);
    }

    private static SymbolTable symbolTable(Map<String, Integer> weights) {
        if (weights == null || !weights.keySet().equals(GameRules.SYMBOLS)) {
            throw new IllegalArgumentException("symbol weights must contain exactly H1..H8, WILD and SC");
        }
        if (!Integer.valueOf(0).equals(weights.get("WILD"))) {
            throw new IllegalArgumentException("WILD direct-draw weight must be zero; WILD is golden-transform only");
        }
        List<String> order = List.of("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "SC", "WILD");
        int[] cumulative = new int[order.size()];
        long total = 0;
        for (int index = 0; index < order.size(); index++) {
            String symbol = order.get(index);
            Integer weight = weights.get(symbol);
            if (weight == null || ("WILD".equals(symbol) ? weight < 0 : weight <= 0)) {
                throw new IllegalArgumentException(symbol + " weight is invalid");
            }
            total += weight;
            if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("symbol weight total is too large");
            cumulative[index] = (int) total;
        }
        if (total <= 0) throw new IllegalArgumentException("symbol weight total must be positive");
        return new SymbolTable(List.copyOf(order), cumulative, (int) total);
    }

    private record SymbolTable(List<String> symbols, int[] cumulative, int total) { }

    /** Installs the explicit per-eligible-card Silver/Gold sampling ratio. */
    public synchronized void configureCardMaterialWeights(int silverWeight, int goldWeight) {
        if (silverWeight < 0 || goldWeight < 0 || (silverWeight == 0 && goldWeight == 0)) {
            throw new IllegalArgumentException("silver/gold card weights must be non-negative and not both zero");
        }
        long total = (long) silverWeight + goldWeight;
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("silver/gold card weight total is too large");
        runtimeSilverCardWeight = silverWeight;
        runtimeGoldCardWeight = goldWeight;
        runtimeCardMaterialTotal = (int) total;
    }

    private int runtimeFreeAward(List<String> board) {
        return GameRules.freeAward(
                CoinMasterResultUtil.evaluate(board, 1, BigDecimal.ONE, 1).scatterCount());
    }

    private String runtimeScenario(RoundPlan round) {
        int fsn = round.deliveries.stream().flatMap(delivery -> delivery.steps.stream())
                .mapToInt(step -> step.fsn).max().orElse(0);
        // Initial award is 12/14/16 from the paid Delivery's terminal visible SC.
        // `fsn > 12` is not evidence of a retrigger when the paid terminal had 4+ SC.
        int initialAward = round.deliveries.isEmpty() ? 0
                : round.deliveries.getFirst().steps.getLast().fsn;
        if (fsn > initialAward) return RoundScenario.FREE_RETRIGGER.name();
        if (fsn > 0) return RoundScenario.FREE_SPINS.name();
        boolean golden = round.deliveries.stream().flatMap(delivery -> delivery.steps.stream())
                .anyMatch(this::hasWinningGolden);
        if (golden) return RoundScenario.GOLDEN_TRANSFORM.name();
        return round.totalWin.signum() > 0
                ? RoundScenario.BASE_WIN.name() : RoundScenario.LOSS.name();
    }

    private boolean hasWinningGolden(SpinStep step) {
        Set<Integer> golden = new HashSet<>(step.gfl);
        return step.wmkl.stream().flatMap(List::stream).flatMap(List::stream)
                .anyMatch(rawCoordinate -> golden.contains(rawCoordinate + 1));
    }

    private static final class RuntimeAccumulator {
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal free = BigDecimal.ZERO;
    }

    private static final class RuntimeCandidateRejected extends RuntimeException { }

    public RoundPlan generateCompleteRound(RoundScenario scenario, String roundKey, String transferId,
                                           int betLevel, BigDecimal betSize, BigDecimal postDebitBalance,
                                           long createdAt) {
        RoundPlan round = new RoundPlan();
        round.roundKey = roundKey;
        round.transferId = transferId;
        round.paidBid = GameRules.GAME_PROTOCOL_ID + "-" + transferId;
        round.betLevel = betLevel;
        round.betSize = betSize;
        round.betAmount = GameRules.betAmount(betLevel, betSize);
        round.postDebitBalance = postDebitBalance;
        round.createdAt = createdAt;
        round.scenario = scenario.name();

        switch (scenario) {
            case LOSS -> buildLoss(round);
            case BASE_WIN -> buildBaseWin(round);
            case GOLDEN_TRANSFORM -> buildGoldenTransform(round);
            case FREE_SPINS -> buildFreeFeature(round, false);
            case FREE_RETRIGGER -> buildFreeFeature(round, true);
        }
        validator.validate(round);
        return round;
    }

    public SpinStep neutralSnapshot(BigDecimal balance) {
        RoundPlan shell = new RoundPlan();
        shell.betLevel = 1;
        shell.betSize = new BigDecimal("0.02");
        for (int attempt = 0; attempt < 2_000; attempt++) {
            List<String> board = runtimeRandomBoard(false);
            CoinMasterResultUtil.Evaluation evaluation = CoinMasterResultUtil.evaluate(
                    board, shell.betLevel, shell.betSize, 1);
            if (evaluation.totalWin().signum() != 0 || evaluation.scatterCount() >= 3) continue;
            SpinStep neutral = step(shell, board, 1, 0, 1, 1, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, balance);
            applyCardLayout(neutral, runtimeInitialCardLayout(board));
            return neutral;
        }
        throw new IllegalStateException("unable to generate a neutral evidence-profile snapshot");
    }

    private void buildLoss(RoundPlan round) {
        SpinStep step = step(round, lossBoard(), 1, 0, 1, 1, 0, 0,
                round.betAmount, BigDecimal.ZERO, BigDecimal.ZERO, round.postDebitBalance);
        applyCardLayout(step, runtimeInitialCardLayout(step.rskl));
        round.deliveries.add(new RoundDelivery("BASE", List.of(step)));
    }

    private void buildBaseWin(RoundPlan round) {
        SpinStep paid = step(round, baseWinBoard(), 1, 0, 1, 0, 0, 0,
                round.betAmount, BigDecimal.ZERO, BigDecimal.ZERO, round.postDebitBalance);
        // This scripted regression isolates the captured Silver-removal cascade path.
        applyCardLayout(paid, decodedCardLayout(paid.rskl, List.of()));
        BigDecimal cumulative = paid.wa;
        paid.rwa = cumulative;

        // Captured scenes 11 -> 12 show that a reel is not redrawn: winners are
        // removed from its five-cell transport queue, the old buffer and surviving
        // visible symbols retain order and fall, and only the new top holes are filled.
        CascadeProjection firstProjection = cascadeBoard(paid, List.of(
                List.of("H4"), List.of("H2"), List.of("H8"), List.of(), List.of()));
        SpinStep secondWin = step(round, firstProjection.board(),
                1, 1, 2, 0, 0, 0,
                BigDecimal.ZERO, cumulative, BigDecimal.ZERO, round.postDebitBalance);
        applyCardLayout(secondWin, new CardLayout(
                firstProjection.silverCoordinates(), firstProjection.goldenCoordinates()));
        cumulative = cumulative.add(secondWin.wa);
        secondWin.rwa = CoinMasterResultUtil.money(cumulative);

        CascadeProjection secondProjection = cascadeBoard(secondWin, List.of(
                List.of("H8"), List.of("H7"), List.of("H6", "H3"), List.of(), List.of()));
        SpinStep cascadeEnd = step(round, secondProjection.board(),
                1, 1, 3, 1, 0, 0,
                BigDecimal.ZERO, cumulative, BigDecimal.ZERO, round.postDebitBalance.add(cumulative));
        applyCardLayout(cascadeEnd, new CardLayout(
                secondProjection.silverCoordinates(), secondProjection.goldenCoordinates()));
        round.totalWin = cumulative;
        round.deliveries.add(new RoundDelivery("BASE", List.of(paid, secondWin, cascadeEnd)));
    }

    /**
     * Explicit demo/test projection of the accepted golden-symbol rule. Frontend coordinate 11
     * is the first visible row of the second reel: it participates in the paid H8 win and
     * becomes a WILD in the next cascade. This scenario is
     * never selected by the production LOSS default or by an invented probability.
     */
    private void buildGoldenTransform(RoundPlan round) {
        SpinStep paid = step(round, goldenPaidWinBoard(), 1, 0, 1, 0, 0, 0,
                round.betAmount, BigDecimal.ZERO, BigDecimal.ZERO, round.postDebitBalance);
        applyCardLayout(paid, decodedCardLayout(paid.rskl, List.of(11)));
        BigDecimal cumulative = paid.wa;
        paid.rwa = cumulative;
        CascadeProjection projection = cascadeBoard(paid, List.of(
                List.of("H7"), List.of(), List.of("H3"), List.of(), List.of()));
        SpinStep cascadeEnd = step(round, projection.board(),
                1, 1, 2, 1, 0, 0,
                BigDecimal.ZERO, cumulative, BigDecimal.ZERO, round.postDebitBalance.add(cumulative));
        applyCardLayout(cascadeEnd, new CardLayout(
                projection.silverCoordinates(), projection.goldenCoordinates()));
        round.totalWin = cumulative;
        round.deliveries.add(new RoundDelivery("BASE", List.of(paid, cascadeEnd)));
    }

    private void buildFreeFeature(RoundPlan round, boolean retrigger) {
        SpinStep trigger = step(round, scatterBoard(), 1, 0, 1, 1, 12, 0,
                round.betAmount, BigDecimal.ZERO, BigDecimal.ZERO, round.postDebitBalance);
        applyCardLayout(trigger, runtimeInitialCardLayout(trigger.rskl));
        round.deliveries.add(new RoundDelivery("BASE", List.of(trigger)));
        int fsn = 12;
        for (int nfsc = 1; nfsc <= fsn; nfsc++) {
            boolean retriggerNow = retrigger && nfsc == 3;
            List<String> board = retriggerNow ? scatterBoard() : lossBoard();
            if (retriggerNow) fsn += 12;
            BigDecimal projectedBalance = nfsc == fsn ? round.postDebitBalance.add(round.totalWin) : round.postDebitBalance;
            SpinStep free = step(round, board, 2, 2, 2, 1, fsn, nfsc,
                    BigDecimal.ZERO, round.totalWin, round.totalWin, projectedBalance);
            applyCardLayout(free, runtimeInitialCardLayout(free.rskl));
            round.deliveries.add(new RoundDelivery("FREE", List.of(free)));
        }
    }

    private SpinStep step(RoundPlan round, List<String> board, int gt, int smallGameType, int rpx, int ss,
                          int fsn, int nfsc, BigDecimal ba, BigDecimal priorRwa, BigDecimal priorFrwa,
                          BigDecimal projectedBalance) {
        CoinMasterResultUtil.Evaluation evaluation = CoinMasterResultUtil.evaluate(
                board, round.betLevel, round.betSize, rpx);
        SpinStep step = new SpinStep();
        step.ba = CoinMasterResultUtil.money(ba);
        step.gt = gt;
        step.small_game_type = smallGameType;
        step.rpx = rpx;
        step.ss = ss;
        step.fsn = fsn;
        step.nfsc = nfsc;
        step.pb = projectedBalance.setScale(2).toPlainString();
        step.rskl = new ArrayList<>(board);
        step.wa = evaluation.totalWin();
        step.rwa = CoinMasterResultUtil.money(priorRwa.add(step.wa));
        step.frwa = gt == 2 ? CoinMasterResultUtil.money(priorFrwa.add(step.wa)) : priorFrwa;
        step.matchDetails = evaluation.matches();
        for (WinMatch match : evaluation.matches()) {
            step.wskl.add(match.symbol);
            step.wmkl.add(match.coordinates);
        }
        return step;
    }

    /** 与前后 Spin 无牌面继承；倍率按新 Spin 模式初始化，金银材质走真实起始牌入口。 */
    public synchronized SpinStep generateIndependentLoss(boolean freeMode) {
        RoundPlan shell = new RoundPlan();
        shell.betLevel = 1;
        shell.betSize = new BigDecimal("0.02");
        int rpx = (freeMode ? GameRules.FREE_RPX : GameRules.BASE_RPX).getFirst();
        SpinStep result = step(shell, lossBoard(), freeMode ? 2 : 1, freeMode ? 2 : 0,
                rpx, 1, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        applyCardLayout(result, runtimeInitialCardLayout(result.rskl));
        return result;
    }

    public static boolean isIndependentLossBoard(List<String> board) {
        var evaluation = CoinMasterResultUtil.evaluate(board, 1, BigDecimal.ONE, 1);
        return evaluation.matches().isEmpty() && evaluation.totalWin().signum() == 0
                && GameRules.freeAward(evaluation.scatterCount()) == 0;
    }

    private List<String> lossBoard(){return losses.generate(this::lossBoardCandidate,random::nextInt);}
    public List<String> lossBoardCandidate() {
        List<String> board = new ArrayList<>(25);
        addReel(board, List.of("H1", "H2", "H3", "H4"));
        addReel(board, List.of("H5", "H6", "H7", "H8"));
        addReel(board, List.of("H1", "H5", "H6", "H7"));
        addReel(board, List.of("H2", "H3", "H7", "H8"));
        addReel(board, List.of("H1", "H4", "H6", "H8"));
        return board;
    }

    private List<String> baseWinBoard() {
        List<String> board = new ArrayList<>(25);
        addScriptedReel(board, "H7", List.of("H1", "H2", "H3", "H8"));
        addScriptedReel(board, "H7", List.of("H4", "H5", "H6", "H8"));
        addScriptedReel(board, "H7", List.of("H7", "H1", "H5", "H8"));
        addScriptedReel(board, "H8", List.of("H2", "H3", "H5", "H6"));
        addScriptedReel(board, "H5", List.of("H1", "H4", "H6", "H7"));
        return board;
    }

    private List<String> goldenPaidWinBoard() {
        List<String> board = new ArrayList<>(25);
        addScriptedReel(board, "H4", List.of("H8", "H1", "H2", "H3"));
        addScriptedReel(board, "H2", List.of("H8", "H4", "H5", "H6"));
        addScriptedReel(board, "H8", List.of("H8", "H5", "H6", "H7"));
        addScriptedReel(board, "H1", List.of("H2", "H3", "H5", "H6"));
        addScriptedReel(board, "H2", List.of("H1", "H4", "H6", "H7"));
        return board;
    }

    /**
     * Applies the captured reel-local cascade projection to an already evaluated Step.
     * Silver/ordinary winning cells are removed. The old buffer and every non-winning cell keep
     * their order and fall toward the last transport row; supplied values fill only the
     * newly exposed top cells. A winning golden cell uses the frontend coordinate
     * (raw wmkl coordinate + 1), becomes WILD, remains in the survivor queue and falls.
     * Non-winning Silver/Gold materials move with their surviving symbols. All 2,429
     * material-eligible top refills in 679 preserved adjacent source transitions are Silver.
     */
    private CascadeProjection cascadeBoard(SpinStep previous, List<List<String>> newTopSymbolsByReel) {
        if (newTopSymbolsByReel.size() != GameRules.REELS) {
            throw new IllegalArgumentException("cascade refill must define every reel");
        }
        Set<Integer> winning = new HashSet<>();
        previous.wmkl.forEach(match -> match.forEach(winning::addAll));
        List<String> next = new ArrayList<>(GameRules.TRANSPORT_CELLS);
        List<Integer> nextSilver = new ArrayList<>();
        List<Integer> nextGolden = new ArrayList<>();
        for (int reel = 0; reel < GameRules.REELS; reel++) {
            List<String> oldReel = previous.rskl.subList(
                    reel * GameRules.TRANSPORT_ROWS, (reel + 1) * GameRules.TRANSPORT_ROWS);
            Set<Integer> removedPositions = new HashSet<>();
            for (int visibleRow = 0; visibleRow < GameRules.VISIBLE_ROWS; visibleRow++) {
                int rawCoordinate = reel * 10 + visibleRow;
                if (!winning.contains(rawCoordinate)) continue;
                int transportPosition = visibleRow + 1;
                if (!previous.gfl.contains(rawCoordinate + 1)) removedPositions.add(transportPosition);
            }

            List<String> refills = newTopSymbolsByReel.get(reel);
            if (refills.size() != removedPositions.size()
                    || refills.stream().anyMatch(symbol -> !GameRules.SYMBOLS.contains(symbol)
                            || "WILD".equals(symbol))) {
                throw new IllegalArgumentException("cascade refill count/symbol differs from removed winners on reel " + reel);
            }
            List<Survivor> survivors = new ArrayList<>();
            for (int position = 0; position < GameRules.TRANSPORT_ROWS; position++) {
                if (removedPositions.contains(position)) continue;
                int directCoordinate = reel * 10 + position;
                boolean transformed = position > 0
                        && winning.contains(reel * 10 + position - 1)
                        && previous.gfl.contains(directCoordinate);
                survivors.add(new Survivor(transformed ? "WILD" : oldReel.get(position),
                        previous.silverCardCoordinates.contains(directCoordinate) && !transformed,
                        previous.gfl.contains(directCoordinate) && !transformed));
            }
            List<String> projected = new ArrayList<>(refills);
            projected.addAll(survivors.stream().map(Survivor::symbol).toList());
            for (int position = 0; position < refills.size(); position++) {
                String symbol = refills.get(position);
                if (reel >= 1 && reel <= 3 && GameRules.PAYING_SYMBOLS.contains(symbol)) {
                    // Source adjacency evidence: cascade arrivals are explicitly Silver.
                    nextSilver.add(reel * 10 + position);
                }
            }
            for (int index = 0; index < survivors.size(); index++) {
                if (survivors.get(index).silver()) nextSilver.add(reel * 10 + refills.size() + index);
                if (survivors.get(index).golden()) nextGolden.add(reel * 10 + refills.size() + index);
            }
            next.addAll(projected);
        }
        return new CascadeProjection(List.copyOf(next), List.copyOf(nextSilver), List.copyOf(nextGolden));
    }

    private record Survivor(String symbol, boolean silver, boolean golden) { }
    private record CardLayout(List<Integer> silverCoordinates, List<Integer> goldenCoordinates) { }
    private record CascadeProjection(List<String> board, List<Integer> silverCoordinates,
                                     List<Integer> goldenCoordinates) { }

    private List<String> scatterBoard() {
        List<String> board = lossBoard();
        board.set(1, "SC");
        board.set(6, "SC");
        board.set(11, "SC");
        return board;
    }

    private void addReel(List<String> board, List<String> visible) {
        board.add(GameRules.PAYING_SYMBOLS.get(random.nextInt(GameRules.PAYING_SYMBOLS.size())));
        List<String> copy = new ArrayList<>(visible);
        // Win boards keep their first H8 coordinate stable; loss boards may vary animation order.
        if (!copy.get(0).equals("H8") || !copy.contains("H1")) Collections.shuffle(copy, random);
        board.addAll(copy);
    }

    private void addStableReel(List<String> board, List<String> visible) {
        board.add(GameRules.PAYING_SYMBOLS.get(random.nextInt(GameRules.PAYING_SYMBOLS.size())));
        board.addAll(visible);
    }

    private void addScriptedReel(List<String> board, String buffer, List<String> visible) {
        board.add(buffer);
        board.addAll(visible);
    }
}
