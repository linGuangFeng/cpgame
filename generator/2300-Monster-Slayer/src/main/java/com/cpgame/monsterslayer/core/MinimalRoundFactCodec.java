package com.cpgame.monsterslayer.core;

import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.Inflater;

/**
 * Round member codec.
 *
 * <p>Ordinary boards stay ASCII {@code MS3N;…}. Feature rounds with HTB1 roles use
 * raw binary {@code MS4B} v2: length-prefixed binary state ({@code MSB1}) plus
 * length-prefixed HTB1 role blobs. No ASCII MS3 state inside MS4B. Legacy MS1/MS2
 * remain readable for drain; legacy MS4/HT1 text is rejected.</p>
 */
public final class MinimalRoundFactCodec {
    private static final String PREFIX_V1 = "MS1";
    private static final String PREFIX_V2 = "MS2";
    private static final String PREFIX_V3 = "MS3";
    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_";

    public static final byte[] MS4B_MAGIC = new byte[] {'M', 'S', '4', 'B'};

    /** Encodes a round. Ordinary MS3 stays ASCII; feature rounds become MS4B v2 (MSB1 state + HTB1 roles). */
    public byte[] encode(GameRuleCore.CompleteRound round) {
        GameRuleCore.validate(round);
        List<byte[]> roles = new ArrayList<>(round.steps().size());
        boolean hasRoles = false;
        for (GameRuleCore.Step step : round.steps()) {
            byte[] r = step.feature().roles();
            if (r == null) r = new byte[0];
            roles.add(r);
            if (r.length > 0) hasRoles = true;
        }
        if (!hasRoles) {
            String state = encodeV3State(round);
            return ascii(state).getBytes(StandardCharsets.US_ASCII);
        }
        return packMs4b(encodeBinaryState(round), roles);
    }

    private static byte[] packMs4b(byte[] state, List<byte[]> roles) {
        int size = 4 + 1 + 4 + state.length + 4;
        for (byte[] r : roles) size += 4 + r.length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(MS4B_MAGIC);
        buf.put((byte) 2);
        buf.putInt(state.length);
        buf.put(state);
        buf.putInt(roles.size());
        for (byte[] r : roles) {
            buf.putInt(r.length);
            buf.put(r);
        }
        return buf.array();
    }

    /** Binary feature state: magic MSB1 + uvarint buyType + steps (board + delta-coded arrays). */
    private static final byte[] MSB1_MAGIC = new byte[] {'M', 'S', 'B', 1};
    private static final int BOARD_SCATTER_CODE = 11;
    private static final int FLAG_HEARTS = 1;
    private static final int FLAG_LOC = 2;
    private static final int FLAG_ANIMALS = 4;
    private static final int FLAG_RBS = 8;

    private static byte[] encodeBinaryState(GameRuleCore.CompleteRound round) {
        if (!round.special()) throw new IllegalArgumentException("MSB1 is for feature rounds only");
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        out.writeBytes(MSB1_MAGIC);
        HuntTrace.writeUvarint(out, round.buyType());
        HuntTrace.writeUvarint(out, round.steps().size());
        int[] previousHearts = new int[0], previousLocCell = new int[0], previousLocId = new int[0];
        int[] previousBl = new int[0], previousIu = new int[0], previousT = new int[0];
        for (GameRuleCore.Step step : round.steps()) {
            HuntTrace.writeUvarint(out, step.gameType());
            HuntTrace.writeUvarint(out, step.nextType());
            writeBoard(out, step.board());
            GameRuleCore.FeatureFacts f = step.feature();
            int flags = 0;
            if (!Arrays.equals(f.hearts(), previousHearts)) flags |= FLAG_HEARTS;
            if (!Arrays.equals(f.locCell(), previousLocCell) || !Arrays.equals(f.locId(), previousLocId)) flags |= FLAG_LOC;
            if (!Arrays.equals(f.bl(), previousBl) || !Arrays.equals(f.iu(), previousIu) || !Arrays.equals(f.t(), previousT))
                flags |= FLAG_ANIMALS;
            if (f.rbs().length > 0) flags |= FLAG_RBS;
            HuntTrace.writeUvarint(out, flags);
            if ((flags & FLAG_HEARTS) != 0) writeIntArray(out, f.hearts());
            if ((flags & FLAG_LOC) != 0) writePairs(out, f.locCell(), f.locId());
            if ((flags & FLAG_ANIMALS) != 0) writeTriples(out, f.bl(), f.iu(), f.t());
            if ((flags & FLAG_RBS) != 0) writeIntArray(out, f.rbs());
            previousHearts = f.hearts();
            previousLocCell = f.locCell();
            previousLocId = f.locId();
            previousBl = f.bl();
            previousIu = f.iu();
            previousT = f.t();
        }
        return out.toByteArray();
    }

    private static void writeBoard(ByteArrayOutputStream out, int[] board) {
        if (board.length != GameRuleCore.CELLS) throw new IllegalArgumentException("board size");
        for (int symbol : board) {
            int code = switch (symbol) {
                case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 -> symbol;
                case GameRuleCore.SCATTER -> BOARD_SCATTER_CODE;
                default -> throw new IllegalArgumentException("unsupported board symbol " + symbol);
            };
            out.write(code);
        }
    }

    private static void writeIntArray(ByteArrayOutputStream out, int[] values) {
        if (values.length > 45) throw new IllegalArgumentException("array overflow");
        HuntTrace.writeUvarint(out, values.length);
        for (int v : values) {
            if (v < 0 || v > 1000) throw new IllegalArgumentException("value out of range");
            HuntTrace.writeUvarint(out, v);
        }
    }

    private static void writePairs(ByteArrayOutputStream out, int[] left, int[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("pair length mismatch");
        HuntTrace.writeUvarint(out, left.length);
        for (int i = 0; i < left.length; i++) {
            HuntTrace.writeUvarint(out, left[i]);
            HuntTrace.writeUvarint(out, right[i]);
        }
    }

    private static void writeTriples(ByteArrayOutputStream out, int[] a, int[] b, int[] c) {
        if (a.length != b.length || a.length != c.length) throw new IllegalArgumentException("triple length mismatch");
        HuntTrace.writeUvarint(out, a.length);
        for (int i = 0; i < a.length; i++) {
            HuntTrace.writeUvarint(out, a[i]);
            HuntTrace.writeUvarint(out, b[i]);
            HuntTrace.writeUvarint(out, c[i]);
        }
    }

    private static String encodeV3State(GameRuleCore.CompleteRound round) {
        if (!round.special()) {
            StringBuilder out = new StringBuilder(PREFIX_V3).append("N;");
            appendCompactBoard(out, round.steps().get(0).board());
            return out.toString();
        }
        char kind = round.buyType() == 0 ? 'S' : 'B';
        int buyDigit = switch (round.buyType()) { case 3 -> 1; case 4 -> 2; case 5 -> 3; default -> 0; };
        StringBuilder out = new StringBuilder(PREFIX_V3).append(kind).append(buyDigit).append(';');
        int[] previousHearts = new int[0], previousLocCell = new int[0], previousLocId = new int[0];
        int[] previousBl = new int[0], previousIu = new int[0], previousT = new int[0];
        for (int s = 0; s < round.steps().size(); s++) {
            if (s > 0) out.append('/');
            GameRuleCore.Step step = round.steps().get(s);
            appendNumber(out, step.gameType());
            appendNumber(out, step.nextType());
            appendCompactBoard(out, step.board());
            GameRuleCore.FeatureFacts f = step.feature();
            int[] hearts = f.hearts(), locCell = f.locCell(), locId = f.locId();
            int[] bl = f.bl(), iu = f.iu(), t = f.t(), rbs = f.rbs();
            if (!java.util.Arrays.equals(hearts, previousHearts)) appendArray(out, '!', hearts);
            if (!java.util.Arrays.equals(locCell, previousLocCell) || !java.util.Arrays.equals(locId, previousLocId))
                appendPairs(out, '@', locCell, locId);
            if (!java.util.Arrays.equals(bl, previousBl) || !java.util.Arrays.equals(iu, previousIu)
                    || !java.util.Arrays.equals(t, previousT)) appendTriples(out, '$', bl, iu, t);
            if (rbs.length > 0) appendArray(out, '%', rbs);
            previousHearts = hearts;
            previousLocCell = locCell;
            previousLocId = locId;
            previousBl = bl;
            previousIu = iu;
            previousT = t;
        }
        return out.toString();
    }

    private static void appendCompactBoard(StringBuilder out, int[] board) {
        for (int symbol : board) out.append(switch (symbol) {
            case 0 -> '0';
            case 1, 2, 3, 4, 5, 6, 7, 8, 9 -> (char) ('0' + symbol);
            case 10 -> 'A';
            case GameRuleCore.SCATTER -> 'S';
            default -> throw new IllegalArgumentException("unsupported compact symbol " + symbol);
        });
    }

    private static void appendArray(StringBuilder out, char tag, int[] values) {
        out.append(tag);
        appendNumber(out, values.length);
        for (int value : values) appendNumber(out, value);
    }

    private static void appendPairs(StringBuilder out, char tag, int[] left, int[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("pair length mismatch");
        out.append(tag);
        appendNumber(out, left.length);
        for (int i = 0; i < left.length; i++) {
            appendNumber(out, left[i]);
            appendNumber(out, right[i]);
        }
    }

    private static void appendTriples(StringBuilder out, char tag, int[] a, int[] b, int[] c) {
        if (a.length != b.length || a.length != c.length) throw new IllegalArgumentException("triple length mismatch");
        out.append(tag);
        appendNumber(out, a.length);
        for (int i = 0; i < a.length; i++) {
            appendNumber(out, a[i]);
            appendNumber(out, b[i]);
            appendNumber(out, c[i]);
        }
    }

    private static void appendNumber(StringBuilder out, int value) {
        if (value < 0 || value >= DIGITS.length()) throw new IllegalArgumentException("compact value out of range: " + value);
        out.append(DIGITS.charAt(value));
    }

    private String encodeV1(GameRuleCore.CompleteRound round) {
        StringBuilder out = new StringBuilder(PREFIX_V1).append(round.special() ? 'S' : 'N').append(';');
        appendStepsV1(out, round.steps());
        return ascii(out.toString());
    }

    private String encodeV2(GameRuleCore.CompleteRound round) {
        char kind = round.buyType() != 0 ? 'B' : 'S';
        int buyDigit = switch (round.buyType()) { case 3 -> 1; case 4 -> 2; case 5 -> 3; default -> 0; };
        StringBuilder out = new StringBuilder(PREFIX_V2).append(kind).append(buyDigit).append(';');
        for (int s = 0; s < round.steps().size(); s++) {
            if (s > 0) out.append('/');
            GameRuleCore.Step step = round.steps().get(s);
            out.append(step.gameType()).append('.').append(step.nextType()).append('.');
            appendBoard10(out, step.board());
            GameRuleCore.FeatureFacts f = step.feature();
            out.append("|H");
            for (int i = 0; i < f.hearts().length; i++) {
                if (i > 0) out.append(',');
                out.append(f.hearts()[i]);
            }
            out.append("|L");
            for (int i = 0; i < f.locCell().length; i++) {
                if (i > 0) out.append(';');
                out.append(f.locCell()[i]).append(':').append(f.locId()[i]);
            }
            out.append("|A");
            for (int i = 0; i < f.bl().length; i++) {
                if (i > 0) out.append(';');
                out.append(f.bl()[i]).append('.').append(f.iu()[i]).append('.').append(f.t()[i]);
            }
            out.append("|R");
            for (int i = 0; i < f.rbs().length; i++) {
                if (i > 0) out.append(',');
                out.append(f.rbs()[i]);
            }
            out.append("|G");
            if (f.roles() != null && f.roles().length > 0)
                throw new IllegalArgumentException("legacy MS2 cannot carry HTB1 roles");
        }
        return ascii(out.toString());
    }

    private static void appendStepsV1(StringBuilder out, List<GameRuleCore.Step> steps) {
        for (int s = 0; s < steps.size(); s++) {
            if (s > 0) out.append('/');
            GameRuleCore.Step step = steps.get(s);
            out.append(step.gameType()).append('.').append(step.nextType()).append('.');
            appendBoard(out, step.board());
        }
    }

    private static void appendBoard(StringBuilder out, int[] board) {
        for (int i = 0; i < board.length; i++) {
            if (i > 0) out.append(',');
            out.append(Integer.toString(board[i], 36).toUpperCase());
        }
    }

    /** ASCII legacy MS1/MS2/MS3 only. MS4B must use {@link #decode(byte[])}. */
    public GameRuleCore.CompleteRound decode(String value) {
        if (value == null) throw new IllegalArgumentException("unknown Monster Slayer member");
        if (value.startsWith("MS4B") || value.startsWith("MS4"))
            throw new IllegalArgumentException("binary MS4B members must be decoded from bytes");
        return decode(value.getBytes(StandardCharsets.US_ASCII));
    }

    public GameRuleCore.CompleteRound decode(byte[] value) {
        if (value == null || value.length == 0) throw new IllegalArgumentException("unknown Monster Slayer member");
        if (startsWith(value, MS4B_MAGIC)) return decodeMs4b(value);
        String text = new String(value, StandardCharsets.US_ASCII);
        if (text.startsWith("MS4"))
            throw new IllegalArgumentException("legacy MS4/HT1 members are not supported; use MS4B");
        if (text.startsWith(PREFIX_V3)) return decodeV3(text);
        if (text.startsWith(PREFIX_V2)) return decodeV2(text);
        if (text.startsWith(PREFIX_V1)) return decodeV1(text);
        throw new IllegalArgumentException("unknown Monster Slayer member");
    }

    private static boolean startsWith(byte[] value, byte[] magic) {
        if (value.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if (value[i] != magic[i]) return false;
        return true;
    }

    private GameRuleCore.CompleteRound decodeMs4b(byte[] value) {
        if (value.length < 9)
            throw new IllegalArgumentException("invalid MS4B header");
        int version = value[4] & 0xFF;
        if (version != 2)
            throw new IllegalArgumentException("unsupported MS4B version " + version + " (need v2 binary state)");
        ByteBuffer buf = ByteBuffer.wrap(value);
        buf.position(5);
        int stateLen = buf.getInt();
        if (stateLen < 0 || buf.remaining() < stateLen)
            throw new IllegalArgumentException("truncated MS4B state");
        byte[] stateBytes = new byte[stateLen];
        buf.get(stateBytes);
        int n = buf.getInt();
        if (n < 0) throw new IllegalArgumentException("invalid MS4B roles count");
        byte[][] roles = new byte[n][];
        for (int i = 0; i < n; i++) {
            int len = buf.getInt();
            if (len < 0 || buf.remaining() < len) throw new IllegalArgumentException("truncated MS4B roles");
            roles[i] = new byte[len];
            buf.get(roles[i]);
        }
        if (buf.remaining() != 0) throw new IllegalArgumentException("trailing MS4B bytes");
        var round = decodeBinaryState(stateBytes, roles);
        GameRuleCore.validate(round);
        return round;
    }

    private GameRuleCore.CompleteRound decodeBinaryState(byte[] state, byte[][] binaryRoles) {
        if (!startsWith(state, MSB1_MAGIC))
            throw new IllegalArgumentException("MS4B v2 state must be MSB1");
        BinCursor c = new BinCursor(state, MSB1_MAGIC.length);
        int buyType = c.uvarint();
        if (buyType != 0 && buyType != 3 && buyType != 4 && buyType != 5)
            throw new IllegalArgumentException("invalid MSB1 buyType");
        int nSteps = c.uvarint();
        if (nSteps < 1 || nSteps > 64) throw new IllegalArgumentException("MSB1 step count out of range");
        List<GameRuleCore.Step> steps = new ArrayList<>(nSteps);
        int[] hearts = new int[0], locCell = new int[0], locId = new int[0];
        int[] bl = new int[0], iu = new int[0], t = new int[0];
        for (int s = 0; s < nSteps; s++) {
            int gameType = c.uvarint();
            int nextType = c.uvarint();
            int[] board = c.board();
            int flags = c.uvarint();
            if ((flags & FLAG_HEARTS) != 0) hearts = c.intArray();
            if ((flags & FLAG_LOC) != 0) {
                int count = c.uvarint();
                locCell = new int[count];
                locId = new int[count];
                for (int i = 0; i < count; i++) {
                    locCell[i] = c.uvarint();
                    locId[i] = c.uvarint();
                }
            }
            if ((flags & FLAG_ANIMALS) != 0) {
                int count = c.uvarint();
                bl = new int[count];
                iu = new int[count];
                t = new int[count];
                for (int i = 0; i < count; i++) {
                    bl[i] = c.uvarint();
                    iu[i] = c.uvarint();
                    t[i] = c.uvarint();
                }
            }
            int[] rbs = new int[0];
            if ((flags & FLAG_RBS) != 0) rbs = c.intArray();
            byte[] roles = new byte[0];
            if (s < binaryRoles.length) roles = binaryRoles[s];
            steps.add(new GameRuleCore.Step(board, gameType, nextType,
                    new GameRuleCore.FeatureFacts(hearts, locCell, locId, bl, iu, t, rbs, roles)));
        }
        if (c.i != state.length) throw new IllegalArgumentException("trailing MSB1 bytes");
        return new GameRuleCore.CompleteRound(true, buyType, steps);
    }

    private static final class BinCursor {
        final byte[] buf; int i;
        BinCursor(byte[] buf, int i) { this.buf = buf; this.i = i; }
        int uvarint() {
            int shift = 0, n = 0;
            while (true) {
                if (i >= buf.length) throw new IllegalArgumentException("truncated MSB1");
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
        int[] intArray() {
            int n = uvarint();
            if (n > 45) throw new IllegalArgumentException("array overflow");
            int[] r = new int[n];
            for (int k = 0; k < n; k++) {
                r[k] = uvarint();
                if (r[k] > 1000) throw new IllegalArgumentException("value out of range");
            }
            return r;
        }
        int[] board() {
            if (i + GameRuleCore.CELLS > buf.length) throw new IllegalArgumentException("truncated board");
            int[] board = new int[GameRuleCore.CELLS];
            for (int k = 0; k < GameRuleCore.CELLS; k++) {
                int code = buf[i++] & 0xFF;
                board[k] = switch (code) {
                    case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 -> code;
                    case BOARD_SCATTER_CODE -> GameRuleCore.SCATTER;
                    default -> throw new IllegalArgumentException("invalid board symbol code");
                };
            }
            return board;
        }
    }

    private GameRuleCore.CompleteRound decodeV3(String value) {
        return decodeV3(value, null);
    }

    private GameRuleCore.CompleteRound decodeV3(String value, byte[][] binaryRoles) {
        if (value.startsWith(PREFIX_V3 + "N;")) {
            if (value.length() != 5 + GameRuleCore.CELLS) throw new IllegalArgumentException("invalid MS3 ordinary length");
            return new GameRuleCore.CompleteRound(false,
                    List.of(new GameRuleCore.Step(parseCompactBoard(value, 5), 0, 0)));
        }
        if (value.length() < 6 || value.charAt(5) != ';') throw new IllegalArgumentException("unknown MS3 member");
        char kind = value.charAt(3);
        int buyDigit = decodeNumber(value.charAt(4));
        int buyType = switch (buyDigit) { case 1 -> 3; case 2 -> 4; case 3 -> 5; default -> 0; };
        if (kind != 'S' && kind != 'B') throw new IllegalArgumentException("invalid MS3 member kind");
        if (kind == 'B' && buyType == 0 || kind == 'S' && buyType != 0)
            throw new IllegalArgumentException("invalid MS3 buy kind");
        if (value.indexOf('~', 6) >= 0)
            throw new IllegalArgumentException("legacy ~roles archive is not supported");
        int memberEnd = value.length();
        byte[][] archivedRoles = binaryRoles == null ? new byte[0][] : binaryRoles;
        List<GameRuleCore.Step> steps = new ArrayList<>();
        int[] hearts = new int[0], locCell = new int[0], locId = new int[0];
        int[] bl = new int[0], iu = new int[0], t = new int[0];
        int cursor = 6;
        while (cursor < memberEnd) {
            if (memberEnd - cursor < 2 + GameRuleCore.CELLS) throw new IllegalArgumentException("truncated MS3 step");
            int gameType = decodeNumber(value.charAt(cursor++));
            int nextType = decodeNumber(value.charAt(cursor++));
            int[] board = parseCompactBoard(value, cursor);
            cursor += GameRuleCore.CELLS;
            int[] rbs = new int[0];
            byte[] roles = new byte[0];
            while (cursor < memberEnd && value.charAt(cursor) != '/') {
                char tag = value.charAt(cursor++);
                if (tag == '&') {
                    throw new IllegalArgumentException("legacy inline roles are not supported");
                }
                int count = readNumber(value, cursor++);
                switch (tag) {
                    case '!' -> {
                        hearts = readArray(value, cursor, count);
                        cursor += count;
                    }
                    case '@' -> {
                        locCell = new int[count];
                        locId = new int[count];
                        for (int i = 0; i < count; i++) {
                            locCell[i] = readNumber(value, cursor++);
                            locId[i] = readNumber(value, cursor++);
                        }
                    }
                    case '$' -> {
                        bl = new int[count];
                        iu = new int[count];
                        t = new int[count];
                        for (int i = 0; i < count; i++) {
                            bl[i] = readNumber(value, cursor++);
                            iu[i] = readNumber(value, cursor++);
                            t[i] = readNumber(value, cursor++);
                        }
                    }
                    case '%' -> {
                        rbs = readArray(value, cursor, count);
                        cursor += count;
                    }
                    default -> throw new IllegalArgumentException("unknown MS3 fact tag " + tag);
                }
            }
            if (roles.length == 0 && steps.size() < archivedRoles.length) roles = archivedRoles[steps.size()];
            steps.add(new GameRuleCore.Step(board, gameType, nextType,
                    new GameRuleCore.FeatureFacts(hearts, locCell, locId, bl, iu, t, rbs, roles)));
            if (cursor < memberEnd) cursor++;
        }
        return new GameRuleCore.CompleteRound(true, buyType, steps);
    }

    private static int[] parseCompactBoard(String value, int offset) {
        if (offset < 0 || offset + GameRuleCore.CELLS > value.length()) throw new IllegalArgumentException("truncated compact board");
        int[] board = new int[GameRuleCore.CELLS];
        for (int i = 0; i < board.length; i++) board[i] = switch (value.charAt(offset + i)) {
            case '0' -> 0;
            case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> value.charAt(offset + i) - '0';
            case 'A' -> 10;
            case 'S' -> GameRuleCore.SCATTER;
            default -> throw new IllegalArgumentException("invalid compact symbol");
        };
        return board;
    }

    private static int[] readArray(String value, int cursor, int count) {
        if (cursor < 0 || count < 0 || cursor + count > value.length()) throw new IllegalArgumentException("truncated MS3 array");
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = decodeNumber(value.charAt(cursor + i));
        return result;
    }

    private static int readNumber(String value, int cursor) {
        if (cursor < 0 || cursor >= value.length()) throw new IllegalArgumentException("truncated MS3 number");
        return decodeNumber(value.charAt(cursor));
    }

    private static int decodeNumber(char encoded) {
        int value = DIGITS.indexOf(encoded);
        if (value < 0) throw new IllegalArgumentException("invalid MS3 number");
        return value;
    }

    private static String[] decodeRolesArchive(String archive) {
        if (archive.isEmpty()) return new String[0];
        int firstColon = archive.indexOf(':');
        if (firstColon <= 0 || !archive.substring(0, firstColon).chars().allMatch(Character::isDigit)) {
            // Compatibility with the short-lived deflated MS3 archive.
            return inflate(archive).split("\u001e", -1);
        }
        List<String> roles = new ArrayList<>();
        int cursor = 0;
        while (cursor < archive.length()) {
            int colon = archive.indexOf(':', cursor);
            if (colon < 0) throw new IllegalArgumentException("truncated MS3 roles length");
            int length;
            try {
                length = Integer.parseInt(archive.substring(cursor, colon));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid MS3 roles length", e);
            }
            cursor = colon + 1;
            if (length < 0 || cursor + length > archive.length())
                throw new IllegalArgumentException("truncated MS3 roles frame");
            roles.add(archive.substring(cursor, cursor + length));
            cursor += length;
        }
        return roles.toArray(String[]::new);
    }

    private static String inflate(String value) {
        byte[] source;
        try {
            source = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid MS3 roles encoding", e);
        }
        Inflater inflater = new Inflater(true);
        inflater.setInput(source);
        byte[] buffer = new byte[1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length * 3);
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    out.write(buffer, 0, count);
                    if (out.size() > 1_000_000) throw new IllegalArgumentException("MS3 roles are too large");
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new IllegalArgumentException("truncated MS3 roles");
                }
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalArgumentException("invalid MS3 roles compression", e);
        } finally {
            inflater.end();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private GameRuleCore.CompleteRound decodeV1(String value) {
        if (value.length() < 6 || value.charAt(4) != ';') throw new IllegalArgumentException("unknown Monster Slayer member");
        boolean special = switch (value.charAt(3)) {
            case 'S' -> true;
            case 'N' -> false;
            default -> throw new IllegalArgumentException("invalid member kind");
        };
        List<GameRuleCore.Step> steps = new ArrayList<>();
        for (String encoded : value.substring(5).split("/", -1)) {
            String[] parts = encoded.split("\\.", 3);
            if (parts.length != 3) throw new IllegalArgumentException("invalid Step header");
            steps.add(new GameRuleCore.Step(parseBoard(parts[2]), Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        }
        return new GameRuleCore.CompleteRound(special, steps);
    }

    private GameRuleCore.CompleteRound decodeV2(String value) {
        if (value.length() < 6 || value.charAt(5) != ';') throw new IllegalArgumentException("unknown Monster Slayer member");
        char kind = value.charAt(3);
        int buyDigit = value.charAt(4) - '0';
        int buyType = switch (buyDigit) { case 1 -> 3; case 2 -> 4; case 3 -> 5; default -> 0; };
        boolean special = kind == 'S' || kind == 'B' || buyType != 0;
        List<GameRuleCore.Step> steps = new ArrayList<>();
        for (String encoded : value.substring(6).split("/", -1)) {
            steps.add(parseFeatureStep(encoded));
        }
        return new GameRuleCore.CompleteRound(special, buyType, steps);
    }

    public GameRuleCore.CompleteRound decodeBuyTemplate(int buyType, String encoded) {
        List<GameRuleCore.Step> steps = new ArrayList<>();
        for (String step : encoded.split("/", -1)) steps.add(parseFeatureStep(step));
        return new GameRuleCore.CompleteRound(true, buyType, steps);
    }

    public static GameRuleCore.Step parseFeatureStep(String encoded) {
        String[] main = encoded.split("\\|", -1);
        if (main.length < 1) throw new IllegalArgumentException("invalid feature step");
        String[] parts = main[0].split("\\.", 3);
        if (parts.length != 3) throw new IllegalArgumentException("invalid Step header");
        int[] hearts = new int[0], locCell = new int[0], locId = new int[0], bl = new int[0], iu = new int[0], t = new int[0], rbs = new int[0];
        byte[] roles = new byte[0];
        for (int i = 1; i < main.length; i++) {
            String block = main[i];
            if (block.isEmpty()) continue;
            char tag = block.charAt(0);
            String body = block.substring(1);
            switch (tag) {
                case 'H' -> hearts = ints(body, ',');
                case 'L' -> {
                    if (body.isEmpty()) break;
                    String[] pairs = body.split(";", -1);
                    locCell = new int[pairs.length];
                    locId = new int[pairs.length];
                    for (int p = 0; p < pairs.length; p++) {
                        String[] kv = pairs[p].split(":", 2);
                        locCell[p] = Integer.parseInt(kv[0]);
                        locId[p] = Integer.parseInt(kv[1]);
                    }
                }
                case 'A' -> {
                    if (body.isEmpty()) break;
                    String[] rows = body.split(";", -1);
                    bl = new int[rows.length];
                    iu = new int[rows.length];
                    t = new int[rows.length];
                    for (int p = 0; p < rows.length; p++) {
                        String[] kv = rows[p].split("\\.", 3);
                        bl[p] = Integer.parseInt(kv[0]);
                        iu[p] = Integer.parseInt(kv[1]);
                        t[p] = Integer.parseInt(kv[2]);
                    }
                }
                case 'R' -> rbs = ints(body, ',');
                case 'G' -> roles = body.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                default -> { }
            }
        }
        return new GameRuleCore.Step(
                parseBoard10(parts[2]),
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                new GameRuleCore.FeatureFacts(hearts, locCell, locId, bl, iu, t, rbs, roles));
    }

    private static int[] parseBoard(String encoded) {
        return parseRadix(encoded, 36);
    }

    private static int[] parseBoard10(String encoded) {
        return parseRadix(encoded, 10);
    }

    private static void appendBoard10(StringBuilder out, int[] board) {
        for (int i = 0; i < board.length; i++) {
            if (i > 0) out.append(',');
            out.append(board[i]);
        }
    }

    private static int[] parseRadix(String encoded, int radix) {
        String[] cells = encoded.split(",", -1);
        if (cells.length != GameRuleCore.CELLS) throw new IllegalArgumentException("invalid board length");
        int[] board = new int[cells.length];
        for (int i = 0; i < board.length; i++) board[i] = Integer.parseInt(cells[i], radix);
        return board;
    }

    private static int[] ints(String body, char sep) {
        if (body == null || body.isEmpty()) return new int[0];
        String[] parts = body.split("\\" + sep, -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    private static String ascii(String value) {
        if (!value.chars().allMatch(c -> c >= 32 && c < 127) || value.startsWith("{") || value.startsWith("["))
            throw new IllegalStateException("member must be minimal ASCII");
        return value;
    }

    public ResultUtil.RoundResult verify(byte[] value) { return ResultUtil.evaluate(decode(value)); }
    public boolean supports(String value) {
        return value != null && (value.startsWith("MS4B") || value.startsWith(PREFIX_V1) || value.startsWith(PREFIX_V2) || value.startsWith(PREFIX_V3));
    }

    public boolean supports(byte[] value) {
        if (value == null) return false;
        if (startsWith(value, MS4B_MAGIC)) return true;
        String text = new String(value, StandardCharsets.US_ASCII);
        return supports(text);
    }
}
