package com.cpgame.monsterslayer.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Canonical weapon facts as raw binary HTB1 (no CSV, no Base64).
 * Layout: magic bytes 'H''T''B' 0x01, then uvarints for Trace/Action fields.
 */
public final class HuntTrace {
    public static final byte[] MAGIC = new byte[] {'H', 'T', 'B', 1};
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 12_000;

    public record Action(int weapon, int direction, int row, int blocked, int health, int multiplier, int next,
            int[] before, int[] after, int[] hearts, int[] animals, int[] split, int[] wild1, int[] wild2) {
        public Action {
            before = before.clone(); after = after.clone(); hearts = hearts.clone(); animals = animals.clone();
            split = split.clone(); wild1 = wild1.clone(); wild2 = wild2.clone();
        }
    }

    public record Trace(int spin, int mode, int buy, List<Action> actions) {
        public Trace { actions = List.copyOf(actions); }
    }

    public static boolean generated(byte[] value) {
        return value != null && value.length >= MAGIC.length
                && value[0] == MAGIC[0] && value[1] == MAGIC[1]
                && value[2] == MAGIC[2] && value[3] == MAGIC[3];
    }

    public static byte[] encode(Trace trace) {
        if (trace.actions().isEmpty() || trace.actions().size() > 4)
            throw new IllegalArgumentException("trace action count out of range");
        if (trace.mode() < 1 || trace.mode() > 4 || trace.spin() < 0 || trace.spin() > 31)
            throw new IllegalArgumentException("trace header out of range");
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.writeBytes(MAGIC);
        writeUvarint(out, trace.spin());
        writeUvarint(out, trace.mode());
        writeUvarint(out, trace.buy());
        writeUvarint(out, trace.actions().size());
        for (Action a : trace.actions()) {
            writeUvarint(out, a.weapon());
            writeUvarint(out, a.direction());
            writeUvarint(out, a.row());
            writeUvarint(out, a.blocked());
            writeUvarint(out, a.health());
            writeUvarint(out, a.multiplier());
            writeUvarint(out, a.next());
            for (int[] v : List.of(a.before(), a.after(), a.hearts(), a.animals(), a.split(), a.wild1(), a.wild2()))
                writeIntArray(out, v);
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("HTB1 trace too large");
        return bytes;
    }

    public static Trace decode(byte[] value) {
        if (!generated(value) || value.length > MAX_BYTES)
            throw new IllegalArgumentException("invalid HTB1 trace");
        Cursor c = new Cursor(value, MAGIC.length);
        int spin = c.uvarint(), mode = c.uvarint(), buy = c.uvarint(), n = c.uvarint();
        if (n < 1 || n > 4 || mode < 1 || mode > 4 || spin > 31)
            throw new IllegalArgumentException("trace header out of range");
        List<Action> actions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            actions.add(new Action(
                    c.uvarint(), c.uvarint(), c.uvarint(), c.uvarint(), c.uvarint(), c.uvarint(), c.uvarint(),
                    c.array(), c.array(), c.array(), c.array(), c.array(), c.array(), c.array()));
        }
        if (c.i != value.length) throw new IllegalArgumentException("trailing HTB1 bytes");
        return new Trace(spin, mode, buy, actions);
    }

    public static ArrayNode roles(byte[] encoded) {
        if (encoded == null || encoded.length == 0) return JSON.createArrayNode();
        if (!generated(encoded))
            throw new IllegalArgumentException("roles must be HTB1 binary");
        Trace t = decode(encoded);
        ArrayNode out = JSON.createArrayNode();
        int previous = 0;
        ObjectNode bundle = null;
        for (Action a : t.actions()) {
            if (a.weapon() != previous) { bundle = out.addObject(); previous = a.weapon(); }
            ObjectNode r = bundle.putObject(Integer.toString(a.direction()));
            r.set("bf", loc(a.before(), t.mode() == 4));
            r.set("af", loc(a.after(), t.mode() == 4));
            ObjectNode f = r.putObject("f");
            ArrayNode animals = f.putArray("a");
            for (int i = 0; i < a.animals().length; i += 3)
                animals.addObject().put("t", a.animals()[i]).put("bl", a.animals()[i + 1]).put("iu", a.animals()[i + 2]);
            f.put("b", 1); f.put("l", 1); f.put("but", t.buy()); f.put("gt", t.mode());
            f.put("next_type", a.next()); f.put("ts", t.spin()); f.put("tw", 0); f.put("m", a.multiplier());
            f.set("loc", loc(a.after(), false));
            f.set("hs", ints(a.hearts()));
            ArrayNode cs = f.putArray("cs");
            cs.addObject().put("t", 1).put("h", 1);
            if (t.actions().stream().anyMatch(x -> x.weapon() == 2)) cs.addObject().put("t", 2).put("h", 2);
            ObjectNode ls = r.putObject("ls");
            ls.putObject("ca").put("t", a.weapon()).put("h", a.health());
            ls.put("dt", a.direction()); ls.put("ln", a.row() + 1); ls.put("ib", a.blocked()); ls.put("rand", 0);
            ObjectNode sp = r.putObject("sp");
            if (a.split().length > 0) sp.set("2", ints(a.split()));
            if (a.wild1().length > 0) sp.set("5", ints(a.wild1()));
            if (a.wild2().length > 0) sp.set("6", ints(a.wild2()));
        }
        return out;
    }

    private static JsonNode loc(int[] values, boolean empty) {
        if (empty) return JSON.createArrayNode();
        ObjectNode n = JSON.createObjectNode();
        for (int i = 0; i < values.length; i++) if (values[i] > 0) n.put(Integer.toString(i), values[i]);
        return n;
    }

    private static ArrayNode ints(int[] values) {
        ArrayNode a = JSON.createArrayNode();
        for (int v : values) a.add(v);
        return a;
    }

    public record Payout(int multiplier, Set<Integer> split, Set<Integer> doubled) {}

    public static Payout payout(GameRuleCore.Step step) {
        int m = 1;
        Set<Integer> split = new HashSet<>(), doubled = new HashSet<>();
        JsonNode finalAnimals = null, finalLoc = null;
        ArrayNode rolesNode = roles(step.feature().roles());
        for (JsonNode role : rolesNode) for (JsonNode a : role) {
            for (JsonNode cell : a.path("sp").path("2")) split.add(cell.asInt());
            JsonNode f = a.path("f");
            m = f.path("m").asInt(1);
            finalAnimals = f.path("a");
            finalLoc = a.path("af");
        }
        if (step.gameType() == 4) {
            int bottom = 0;
            for (JsonNode role : rolesNode) for (JsonNode a : role)
                if (a.path("ls").path("ln").asInt() == 3) bottom++;
            m = Math.multiplyExact(m, 1 << bottom);
        } else if (finalAnimals != null) {
            boolean upgraded = false;
            for (JsonNode a : finalAnimals)
                if (a.path("t").asInt() == 1 && a.path("iu").asInt() == 1) upgraded = true;
            if (upgraded && finalLoc != null && finalLoc.isObject())
                for (var it = finalLoc.fields(); it.hasNext(); ) {
                    var e = it.next();
                    if (e.getValue().asInt() == 1) doubled.add(Integer.parseInt(e.getKey()));
                }
        }
        if (m < 1 || m > 20) throw new IllegalArgumentException("invalid feature multiplier");
        return new Payout(m, Set.copyOf(split), Set.copyOf(doubled));
    }

    private static void writeIntArray(ByteArrayOutputStream out, int[] values) {
        if (values.length > 45) throw new IllegalArgumentException("trace array overflow");
        writeUvarint(out, values.length);
        for (int v : values) {
            if (v < 0 || v > 1000) throw new IllegalArgumentException("trace value out of range");
            writeUvarint(out, v);
        }
    }

    static void writeUvarint(ByteArrayOutputStream out, int n) {
        if (n < 0) throw new IllegalArgumentException("uvarint must be >= 0");
        int v = n;
        while (true) {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) out.write(b | 0x80);
            else { out.write(b); return; }
        }
    }

    private static final class Cursor {
        final byte[] buf; int i;
        Cursor(byte[] buf, int i) { this.buf = buf; this.i = i; }
        int uvarint() {
            int shift = 0, n = 0;
            while (true) {
                if (i >= buf.length) throw new IllegalArgumentException("truncated HTB1");
                int b = buf[i++] & 0xFF;
                n |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (n < 0) throw new IllegalArgumentException("uvarint overflow");
                    return n;
                }
                shift += 7;
                if (shift > 31) throw new IllegalArgumentException("uvarint too large");
            }
        }
        int[] array() {
            int n = uvarint();
            if (n > 45) throw new IllegalArgumentException("trace array overflow");
            int[] r = new int[n];
            for (int k = 0; k < n; k++) {
                r[k] = uvarint();
                if (r[k] > 1000) throw new IllegalArgumentException("trace value out of range");
            }
            return r;
        }
    }
}
