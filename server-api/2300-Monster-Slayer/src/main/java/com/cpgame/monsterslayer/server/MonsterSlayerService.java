package com.cpgame.monsterslayer.server;

import com.cpgame.monsterslayer.core.GameRuleCore;
import com.cpgame.monsterslayer.core.ResultUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class MonsterSlayerService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000L);
    // Initial display has no wager and does not consume Redis results.
    private static final GameRuleCore.CompleteRound IDLE = createIdle();
    private static GameRuleCore.CompleteRound createIdle() {
        var board = new com.cpgame.monsterslayer.generator.MonsterSlayerBoardGenerator().generateLossCandidate();
        var round = new GameRuleCore.CompleteRound(false, List.of(new GameRuleCore.Step(board, 0, 0)));
        if (ResultUtil.evaluate(round).multiplierCenti() != 0)
            throw new IllegalStateException("idle screen must have no award");
        return round;
    }
    private final RedisRoundStore rounds;
    private final BigDecimal initialBalance;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    MonsterSlayerService(RedisRoundStore rounds, BigDecimal initialBalance) {
        this.rounds = rounds;
        this.initialBalance = money(initialBalance);
    }

    ObjectNode config(Map<String, String> form, String authority) {
        String language = normalizeLanguage(form.get("language"));
        ObjectNode d = JSON.createObjectNode();
        d.putObject("game_address").putObject("ship_address_config");
        ObjectNode gi = d.putObject("game_info");
        ArrayNode bets = gi.putArray("bet_gold");
        bets.add(.2).add(2).add(20);
        gi.put("buy_free_max_bet", 0);
        gi.put("default_bet_gold", 0);
        gi.put("default_level", 10);
        ArrayNode ways = gi.putArray("game_way");
        ObjectNode w = ways.addObject();
        w.put("max_bet_gold", "0.00");
        w.put("min_bet_gold", "0.00");
        w.put("way_id", 230010000);
        w.put("win_multi", "1.00");
        gi.put("gid", 2300);
        gi.put("least_gold", 0);
        gi.put("name", "Monster Slayer");
        gi.put("status", "1");
        ObjectNode gs = d.putObject("game_server");
        gs.put("ngs_switch", 0);
        gs.put("api_authority", authority);
        ObjectNode ic = d.putObject("initial_config");
        ic.put("bd_bet_count", 2);
        ic.put("current_sys_time", Instant.now().getEpochSecond());
        ic.put("is_debug", false);
        ic.put("is_stopgs", 0);
        ic.put("user_on_hook_time", 600);
        ic.put("version", 1745909504);
        d.put("language", language);
        d.put("r", 1);
        d.put("zone", 0);
        return ok(d);
    }

    ObjectNode user(Map<String, String> form) {
        Session s = session(form);
        ObjectNode d = JSON.createObjectNode();
        d.put("currency_symbol", "R$");
        d.put("day_first_login", 0);
        d.putNull("first_gold");
        d.put("gid", 2300);
        d.put("gold", s.balance);
        d.put("is_guide", 0);
        d.put("nickname", "local-player");
        d.put("token", s.key);
        d.put("total_recharge", "0");
        d.put("uid", s.uid);
        ObjectNode gc = d.putObject("user_config").putObject("game_config");
        gc.putArray("ac");
        gc.put("open_auto_spin", 1);
        gc.put("open_free_buy", 1);
        gc.put("open_music", 1);
        gc.put("open_paytable", 0);
        gc.put("open_sound", 1);
        return ok(d);
    }

    ObjectNode activity() {
        ObjectNode d = JSON.createObjectNode();
        d.putObject("free").putArray("act_list");
        d.putArray("list");
        return ok(d);
    }

    ObjectNode init(Map<String, String> form) throws IOException {
        Session s = session(form);
        synchronized (s) {
            if (s.lastResponse != null) return ok(s.lastResponse.deepCopy());
            GameRuleCore.CompleteRound idle = IDLE;
            return ok(project(s, idle, 0, new BigDecimal("0.2"), 10, false, 0L, 1, BigDecimal.ZERO, 1));
        }
    }

    ObjectNode spin(Map<String, String> form) throws IOException {
        requireGame(form);
        Session s = session(form);
        synchronized (s) {
            int type = integer(form.getOrDefault("type", s.active == null ? "1" : "2"), "type");
            if (s.active != null) {
                if (type != 2) throw new IllegalArgumentException("active feature requires type=2");
                return deliverActive(s);
            }
            BigDecimal bet = decimal(form.getOrDefault("bet", "0.2"), "bet");
            int level = integer(form.getOrDefault("level", "10"), "level");
            if (bet.signum() <= 0 || level <= 0) throw new IllegalArgumentException("bet and level must be positive");
            RedisRoundStore.Claimed claimed;
            int requestType;
            int buyMultiple;
            if (type == 3 || type == 4 || type == 5) {
                claimed = rounds.claimBuy(type);
                requestType = type;
                buyMultiple = GameRuleCore.buyMultiple(type);
            } else if (type == 1) {
                claimed = rounds.claimPaidRound();
                requestType = 1;
                buyMultiple = 1;
            } else {
                throw new IllegalArgumentException("unsupported type " + type);
            }
            s.active = new Active(claimed.round(), bet, level, buyMultiple, requestType, new ArrayList<>(), 0, BigDecimal.ZERO, IDS.incrementAndGet());
            return deliverActive(s);
        }
    }

    private ObjectNode deliverActive(Session s) {
        Active a = s.active;
        boolean paidStart = a.index == 0;
        ObjectNode data = project(s, a.round, a.index, a.bet, a.level, paidStart, a.rootOid, a.buyMultiple, a.cumulativeWin, a.requestType);
        a.responses.add(data.deepCopy());
        a.cumulativeWin = a.cumulativeWin.add(data.path("tw").decimalValue());
        a.index++;
        if (a.index >= a.round.steps().size()) {
            s.history.add(0, historyRow(a));
            s.active = null;
        }
        s.lastResponse = data.deepCopy();
        return ok(data);
    }

    private ObjectNode project(Session s, GameRuleCore.CompleteRound round, int index, BigDecimal bet, int level,
            boolean paidStart, long rootOid, int buyMultiple, BigDecimal priorFeatureWin, int requestType) {
        GameRuleCore.Step step = round.steps().get(index);
        ResultUtil.StepResult result = ResultUtil.evaluateStep(step);
        BigDecimal baseBet = money(bet.multiply(BigDecimal.valueOf(level)));
        BigDecimal win = money(baseBet.multiply(BigDecimal.valueOf(result.multiplierCenti())).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
        BigDecimal charge = paidStart ? money(baseBet.multiply(BigDecimal.valueOf(buyMultiple))) : BigDecimal.ZERO;
        BigDecimal sg = s.balance;
        s.balance = money(s.balance.subtract(charge).add(win));
        long oid = rootOid == 0 ? IDS.incrementAndGet() : index == 0 ? rootOid : IDS.incrementAndGet();
        ObjectNode d = JSON.createObjectNode();
        d.put("b", bet);
        d.put("bg", paidStart && buyMultiple > 1 ? money(baseBet.multiply(BigDecimal.valueOf(buyMultiple))) : baseBet);
        d.put("cg", money(win.subtract(charge)));
        d.put("cl", 0);
        d.put("eg", s.balance);
        GameRuleCore.Step previous = index > 0 ? round.steps().get(index - 1) : null;
        d.set("f", feature(step, previous, index, bet, level, priorFeatureWin.add(win)));
        d.put("l", level);
        d.put("o", BigDecimal.valueOf(result.multiplierCenti(), 2));
        d.put("oid", oid);
        ObjectNode res = d.putObject("res");
        ArrayNode ps = res.putArray("ps");
        for (int v : step.board()) ps.add(v);
        ArrayNode tws = res.putArray("tws");
        for (int v : result.winningCells()) tws.add(v);
        ArrayNode wa = res.putArray("wa");
        int totalWays = 0;
        for (ResultUtil.Award award : result.awards()) {
            ObjectNode x = wa.addObject();
            x.put("n", award.reels());
            BigDecimal awardWin = money(baseBet.multiply(BigDecimal.valueOf((long) award.payoutCenti() * award.ways())).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            x.put("tw", awardWin);
            ArrayNode ws = x.putArray("ws");
            for (int v : award.cells()) ws.add(v);
            x.put("wy", award.ways());
            totalWays += award.ways();
        }
        d.put("sg", sg);
        d.put("small_game_type", index == 0 ? 0 : 2);
        d.put("t", paidStart ? requestType : 2);
        d.put("tw", win);
        d.put("twy", totalWays);
        d.put("u", Long.toString(s.uid));
        return d;
    }

    private ObjectNode feature(GameRuleCore.Step step, GameRuleCore.Step previous, int index,
            BigDecimal bet, int level, BigDecimal cumulative) {
        ObjectNode f = JSON.createObjectNode();
        GameRuleCore.FeatureFacts facts = step.feature();
        ArrayNode a = f.putArray("a");
        int[] bl = facts.bl(), iu = facts.iu(), t = facts.t();
        if (bl.length == 0 && step.gameType() > 0) {
            int animals = step.gameType() >= 3 ? 3 : 2;
            for (int i = 0; i < animals; i++) {
                ObjectNode animal = a.addObject();
                animal.put("bl", 0);
                animal.put("iu", 0);
                animal.put("t", i + 1);
            }
        } else {
            for (int i = 0; i < bl.length; i++) {
                ObjectNode animal = a.addObject();
                animal.put("bl", bl[i]);
                animal.put("iu", iu[i]);
                animal.put("t", t[i]);
            }
        }
        f.put("gt", step.gameType());
        ArrayNode h = f.putArray("h");
        int[] hearts = facts.hearts();
        if (hearts.length == 0 && step.gameType() > 0) {
            int n = Math.max(a.size(), 1);
            for (int i = 0; i < n; i++) h.add(step.nextType() == 0 ? 0 : 1);
        } else {
            for (int value : hearts) h.add(value);
        }
        int[] cells = facts.locCell(), ids = facts.locId();
        boolean superReward = step.gameType() == 4;
        // Original wire: empty loc is [] ; gt=4 also sends outer loc as [].
        if (cells.length == 0 || superReward) f.set("loc", JSON.createArrayNode());
        else f.set("loc", locObject(cells, ids));
        f.put("nt", step.nextType());
        f.set("r", storedRoles(facts.roles(), bet, level, cumulative, step, previous, index, a, h, cells, ids, facts.rbs()));
        ArrayNode rbs = f.putArray("rbs");
        for (int value : facts.rbs()) rbs.add(value);
        f.put("tw", cumulative);
        return f;
    }

    private JsonNode storedRoles(String roles, BigDecimal bet, int level, BigDecimal cumulative,
            GameRuleCore.Step step, GameRuleCore.Step previous, int index, ArrayNode animals, ArrayNode hearts,
            int[] locCells, int[] locIds, int[] rbs) {
        if (roles != null && !roles.isEmpty()) {
            try {
                return JSON.readTree(roles);
            } catch (Exception e) {
                throw new IllegalStateException("stored hunt roles are not valid JSON", e);
            }
        }
        return huntRoles(step, previous, index, animals, hearts, locCells, locIds, rbs, bet, level, cumulative);
    }

    private ArrayNode huntRoles(GameRuleCore.Step step, GameRuleCore.Step previous, int index,
            ArrayNode animals, ArrayNode hearts, int[] locCells, int[] locIds, int[] rbs,
            BigDecimal bet, int level, BigDecimal cumulative) {
        ArrayNode r = JSON.createArrayNode();
        if (index == 0 || step.gameType() == 0) return r;
        ObjectNode innerLoc = locObject(locCells, locIds);
        JsonNode af = step.gameType() == 4 ? JSON.createArrayNode() : innerLoc.deepCopy();
        int[] currentHearts = toInts(hearts);
        int[] previousHearts = previous == null ? currentHearts : previous.feature().hearts();
        if (previousHearts.length == 0) previousHearts = currentHearts;
        boolean dropped = heartDropped(previousHearts, currentHearts);
        int[] target = rbs.length > 0 ? rbs : locCells;
        int monsterId = monsterAt(locCells, locIds, target.length > 0 ? target[0] : -1, animals);
        int ln = laneFor(animals, monsterId, currentHearts.length);
        int heart = ln - 1 < currentHearts.length ? currentHearts[ln - 1] : 1;
        ObjectNode sp = weaponPath(step.gameType(), target, locCells);
        if (step.gameType() == 4) {
            r.add(roleObject("1", af, innerLoc, animals, previousHearts.length == 0 ? currentHearts : previousHearts,
                    bet, level, step, index, cumulative, ln, 1, 1, heart, monsterId, JSON.createObjectNode()));
            ObjectNode reelFive = JSON.createObjectNode();
            ArrayNode cells = reelFive.putArray("2");
            cells.add(12).add(13).add(14);
            r.add(roleObject("1", af, innerLoc, animals, currentHearts, bet, level, step, index, cumulative,
                    Math.max(1, ln - 1), 1, 1, heart, monsterId, reelFive));
            return r;
        }
        if (step.gameType() >= 3) {
            r.add(roleObject("1", af, innerLoc, animals, previousHearts, bet, level, step, index, cumulative,
                    ln, 1, dropped ? 0 : 1, heart, monsterId, sp));
            r.add(roleObject("1", af, innerLoc, animals, currentHearts, bet, level, step, index, cumulative,
                    ln, 1, 1, heart, monsterId, sp.deepCopy()));
            return r;
        }
        ObjectNode bundle = r.addObject();
        if (dropped) {
            bundle.set("1", roleBody(af, innerLoc, animals, previousHearts, bet, level, step, index, cumulative,
                    ln, 1, 0, heart, monsterId, sp));
            bundle.set("2", roleBody(af, innerLoc, animals, currentHearts, bet, level, step, index, cumulative,
                    ln, 2, 0, heart, monsterId, sp.deepCopy()));
        } else {
            bundle.set("1", roleBody(af, innerLoc, animals, currentHearts, bet, level, step, index, cumulative,
                    ln, 1, 1, heart, monsterId, sp));
        }
        return r;
    }

    private ObjectNode roleObject(String key, JsonNode af, ObjectNode innerLoc,
            ArrayNode animals, int[] hs, BigDecimal bet, int level, GameRuleCore.Step step, int index,
            BigDecimal cumulative, int ln, int dt, int ib, int heart, int monsterId, ObjectNode sp) {
        ObjectNode wrap = JSON.createObjectNode();
        wrap.set(key, roleBody(af, innerLoc, animals, hs, bet, level, step, index, cumulative, ln, dt, ib, heart, monsterId, sp));
        return wrap;
    }

    private ObjectNode roleBody(JsonNode af, ObjectNode innerLoc, ArrayNode animals,
            int[] hs, BigDecimal bet, int level, GameRuleCore.Step step, int index, BigDecimal cumulative,
            int ln, int dt, int ib, int heart, int monsterId, ObjectNode sp) {
        ObjectNode role = JSON.createObjectNode();
        role.set("af", af.deepCopy());
        role.set("bf", af.deepCopy());
        ObjectNode rf = role.putObject("f");
        rf.set("a", animals.deepCopy());
        rf.put("b", bet);
        rf.put("but", 0);
        rf.putArray("cs").addObject().put("h", heart).put("t", monsterId);
        rf.put("gt", step.gameType());
        ArrayNode hsNode = rf.putArray("hs");
        for (int value : hs) hsNode.add(value);
        rf.put("l", level);
        rf.set("loc", innerLoc.deepCopy());
        rf.put("m", Math.max(1, index));
        rf.put("next_type", step.nextType());
        rf.put("ts", Math.max(0, index - 1));
        rf.put("tw", cumulative);
        ObjectNode ls = role.putObject("ls");
        ls.putObject("ca").put("h", heart).put("t", monsterId);
        ls.put("dt", dt);
        ls.put("ib", ib);
        ls.put("ln", ln);
        ls.put("rand", 100 + Math.floorMod(index * 137 + ln * 41 + monsterId * 17, 900));
        if (sp.size() == 0 && ib == 1 && step.gameType() < 4) role.set("sp", JSON.createArrayNode());
        else role.set("sp", sp);
        return role;
    }

    private static ObjectNode locObject(int[] cells, int[] ids) {
        ObjectNode loc = JSON.createObjectNode();
        for (int i = 0; i < cells.length; i++) loc.put(Integer.toString(cells[i]), ids[i]);
        return loc;
    }

    private static ObjectNode weaponPath(int gameType, int[] target, int[] locCells) {
        ObjectNode sp = JSON.createObjectNode();
        int[] cells = target.length > 0 ? target : locCells;
        if (cells.length == 0 || gameType == 4) return sp;
        ArrayNode path = sp.putArray("5");
        for (int cell : cells) path.add(cell);
        return sp;
    }

    private static int monsterAt(int[] locCells, int[] locIds, int cell, ArrayNode animals) {
        for (int i = 0; i < locCells.length; i++) if (locCells[i] == cell) return locIds[i];
        if (locIds.length > 0) return locIds[0];
        if (animals.size() > 0) return animals.get(0).path("t").asInt(1);
        return 1;
    }

    private static int laneFor(ArrayNode animals, int monsterId, int heartCount) {
        for (int i = 0; i < animals.size(); i++) {
            if (animals.get(i).path("t").asInt() == monsterId) return i + 1;
        }
        int lanes = Math.max(1, Math.max(animals.size(), heartCount));
        int id = monsterId;
        if (id < 1) id = 1;
        if (id > lanes) id = lanes;
        return id;
    }

    private static boolean heartDropped(int[] previous, int[] current) {
        int n = Math.min(previous.length, current.length);
        for (int i = 0; i < n; i++) if (current[i] < previous[i]) return true;
        return false;
    }

    private static int[] toInts(ArrayNode node) {
        int[] out = new int[node.size()];
        for (int i = 0; i < out.length; i++) out[i] = node.get(i).asInt();
        return out;
    }

    ObjectNode historySummary(Map<String, String> form) {
        Session s = session(form);
        ObjectNode d = JSON.createObjectNode();
        ArrayNode list = d.putArray("list");
        BigDecimal bet = BigDecimal.ZERO, change = BigDecimal.ZERO;
        for (ObjectNode row : s.history) {
            bet = bet.add(row.path("bg").decimalValue());
            change = change.add(row.path("cg").decimalValue());
        }
        ObjectNode day = list.addObject();
        day.put("bet_gold", money(bet));
        day.put("change_gold", money(change));
        day.put("day", Instant.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toEpochSecond());
        ObjectNode stats = d.putObject("statistics");
        stats.put("total_bet_gold", money(bet));
        stats.put("total_change_gold", money(change));
        return ok(d);
    }

    ObjectNode historyDetail(Map<String, String> form) {
        Session s = session(form);
        ObjectNode d = JSON.createObjectNode();
        ArrayNode list = d.putArray("list");
        int pageSize = Math.max(1, integer(form.getOrDefault("page_size", "50"), "page_size"));
        for (int i = 0; i < Math.min(pageSize, s.history.size()); i++) list.add(s.history.get(i).deepCopy());
        BigDecimal bet = BigDecimal.ZERO, change = BigDecimal.ZERO;
        for (ObjectNode row : s.history) {
            bet = bet.add(row.path("bg").decimalValue());
            change = change.add(row.path("cg").decimalValue());
        }
        ObjectNode stats = d.putObject("statistics");
        stats.put("total_bet_gold", money(bet));
        stats.put("total_change_gold", money(change));
        return ok(d);
    }

    ObjectNode balance(Map<String, String> form) {
        Session s = session(form);
        ObjectNode d = JSON.createObjectNode();
        d.put("balance", s.balance);
        d.put("currency", "BRL");
        return ok(d);
    }

    ObjectNode status(Map<String, String> form) {
        Session s = session(form);
        ObjectNode d = JSON.createObjectNode();
        d.put("gameId", 2300);
        d.put("status", "READY_FOR_ACCEPTANCE");
        d.put("acceptanceReady", true);
        d.put("ordinaryModelValidated", true);
        d.put("specialModelValidated", false);
        d.put("buyModelValidated", true);
        d.put("rulesVersion", GameRuleCore.RULES_VERSION);
        d.put("rulesHash", GameRuleCore.RULES_HASH);
        d.put("resultSource", "REDIS_COMPLETE_ROUND_ONLY");
        d.put("runtimeFixtureUse", false);
        d.put("activeRound", s.active != null);
        d.put("balance", s.balance);
        return ok(d);
    }

    private ObjectNode historyRow(Active a) {
        ObjectNode row = a.responses.get(0).deepCopy();
        row.put("order_id", a.rootOid + "-2300");
        ArrayNode results = row.putArray("results");
        BigDecimal total = BigDecimal.ZERO;
        for (ObjectNode response : a.responses) {
            results.add(response.deepCopy());
            total = total.add(response.path("cg").decimalValue());
        }
        row.put("cg", money(total));
        row.put("change_gold", money(total));
        row.put("time", Instant.now().getEpochSecond());
        return row;
    }

    private Session session(Map<String, String> form) {
        String key = first(form.get("token"), form.get("t"), "local-session");
        return sessions.computeIfAbsent(key, k -> new Session(k, Math.abs((long) k.hashCode()) + 23000000L, initialBalance));
    }

    static ObjectNode ok(ObjectNode data) {
        ObjectNode root = JSON.createObjectNode();
        root.put("code", 0);
        root.set("data", data);
        root.put("msg", "success");
        root.put("time", Long.toString(Instant.now().getEpochSecond()));
        return root;
    }

    static ObjectNode error(int code, String message) {
        ObjectNode root = JSON.createObjectNode();
        root.put("code", code);
        root.putObject("data");
        root.put("msg", message);
        root.put("time", Long.toString(Instant.now().getEpochSecond()));
        return root;
    }

    private static void requireGame(Map<String, String> f) {
        if (!"2300".equals(f.getOrDefault("gid", "2300"))) throw new IllegalArgumentException("gid must be 2300");
    }
    private static int integer(String v, String n) {
        try { return Integer.parseInt(v); } catch (Exception e) { throw new IllegalArgumentException(n + " must be integer"); }
    }
    private static BigDecimal decimal(String v, String n) {
        try { return new BigDecimal(v); } catch (Exception e) { throw new IllegalArgumentException(n + " must be decimal"); }
    }
    private static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
    private static String first(String... v) { for (String s : v) if (s != null && !s.isBlank()) return s; return ""; }
    private static String normalizeLanguage(String v) {
        if (v == null || v.isBlank()) return "en-us";
        return switch (v.toLowerCase()) {
            case "en" -> "en-us";
            case "pt", "pt-br" -> "pt-pt";
            case "zh" -> "zh-cn";
            case "es" -> "es-es";
            case "fr" -> "fr-fr";
            case "ko" -> "ko-ko";
            case "ru" -> "ru-ru";
            case "th" -> "th-th";
            case "tr" -> "tr-tr";
            case "vi" -> "vi-vn";
            case "id" -> "id-id";
            case "bn" -> "bn-bd";
            case "hi" -> "hi-in";
            default -> v.toLowerCase();
        };
    }

    private static final class Session {
        final String key;
        final long uid;
        BigDecimal balance;
        Active active;
        ObjectNode lastResponse;
        final List<ObjectNode> history = new ArrayList<>();
        Session(String key, long uid, BigDecimal balance) { this.key = key; this.uid = uid; this.balance = balance; }
    }

    private static final class Active {
        final GameRuleCore.CompleteRound round;
        final BigDecimal bet;
        final int level, buyMultiple, requestType;
        final List<ObjectNode> responses;
        int index;
        BigDecimal cumulativeWin;
        final long rootOid;
        Active(GameRuleCore.CompleteRound round, BigDecimal bet, int level, int buyMultiple, int requestType,
                List<ObjectNode> responses, int index, BigDecimal cumulativeWin, long rootOid) {
            this.round = round;
            this.bet = bet;
            this.level = level;
            this.buyMultiple = buyMultiple;
            this.requestType = requestType;
            this.responses = responses;
            this.index = index;
            this.cumulativeWin = cumulativeWin;
            this.rootOid = rootOid;
        }
    }
}
