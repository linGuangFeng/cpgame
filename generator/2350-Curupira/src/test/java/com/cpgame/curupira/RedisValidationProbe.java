package com.cpgame.curupira;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRound;
import com.cpgame.curupira.model.RoundAnalysis;
import com.cpgame.curupira.redis.RedisContractGate;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 从隔离 Redis 读回正式 Loader 结果，并以 Codec 与 ResultUtil 独立复核。 */
public final class RedisValidationProbe {
    private RedisValidationProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("用法：RedisValidationProbe host port database perBucketCap");
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int database = Integer.parseInt(args[2]);
        int cap = Integer.parseInt(args[3]);
        RedisContractGate keys = new RedisContractGate();
        MinimalFactCodec codec = new MinimalFactCodec();
        ResultUtil resultUtil = new ResultUtil();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int memberCount = 0;
        int trimmedBuckets = 0;

        try (RespClient redis = new RespClient(host, port, database)) {
            List<byte[]> ratios = redis.array("ZRANGE", keys.normalIndex(2350), "0", "-1");
            if (ratios.isEmpty()) throw new IllegalStateException("实际倍率索引为空");
            for (byte[] ratioBytes : ratios) {
                int multiplier = Integer.parseInt(text(ratioBytes));
                if (multiplier <= 0) throw new IllegalStateException("0 倍或负倍数进入索引");
                String listKey = keys.resultKey("ORDINARY_PAID", multiplier, 2350);
                List<byte[]> members = redis.array("LRANGE", listKey, "0", "-1");
                if (members.isEmpty() || members.size() > cap) {
                    throw new IllegalStateException("倍率桶容量错误：" + multiplier + " -> " + members.size());
                }
                if (members.size() == cap) trimmedBuckets++;
                for (byte[] member : members) {
                    CompleteRound round = codec.decodeRound(member);
                    RoundAnalysis analysis = resultUtil.analyzeRound(round);
                    if (analysis.actualMultiplier() != multiplier
                            || !"ORDINARY_PAID".equals(analysis.resultPool())) {
                        throw new IllegalStateException("Codec/ResultUtil 反推与 Redis 倍率桶不一致");
                    }
                    digest.update(ByteBuffer.allocate(4).putInt(member.length).array());
                    digest.update(member);
                    memberCount++;
                }
            }
            if (trimmedBuckets == 0) throw new IllegalStateException("没有倍率桶达到裁剪容量，无法证明 LTRIM 生效");
            if (redis.array("ZRANGE", keys.normalIndex(2350), "0", "-1")
                    .stream().map(RedisValidationProbe::text).anyMatch("0"::equals)) {
                throw new IllegalStateException("发现 0 倍索引");
            }
            System.out.printf("PROBE_RESULT=PASS buckets=%d members=%d trimmedBuckets=%d digest=%s%n",
                    ratios.size(), memberCount, trimmedBuckets, HexFormat.of().formatHex(digest.digest()));
        }
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static final class RespClient implements AutoCloseable {
        private final Socket socket = new Socket();
        private final InputStream in;
        private final OutputStream out;

        private RespClient(String host, int port, int database) throws IOException {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setSoTimeout(30_000);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());
            if (database != 0) command("SELECT", Integer.toString(database));
        }

        private List<byte[]> array(String... args) throws IOException {
            Object response = command(args);
            if (!(response instanceof List<?> values)) throw new IOException("Redis 返回值不是数组");
            List<byte[]> result = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof byte[] bytes)) throw new IOException("Redis 数组成员不是 bulk string");
                result.add(bytes);
            }
            return result;
        }

        private Object command(String... args) throws IOException {
            out.write(utf8("*" + args.length + "\r\n"));
            for (String arg : args) {
                byte[] bytes = utf8(arg);
                out.write(utf8("$" + bytes.length + "\r\n"));
                out.write(bytes);
                out.write(utf8("\r\n"));
            }
            out.flush();
            return read();
        }

        private Object read() throws IOException {
            int prefix = in.read();
            if (prefix < 0) throw new EOFException("Redis 提前关闭连接");
            return switch (prefix) {
                case '+' -> line();
                case '-' -> throw new IOException("Redis 错误：" + line());
                case ':' -> Long.parseLong(line());
                case '$' -> bulk();
                case '*' -> arrayResponse();
                default -> throw new IOException("非法 RESP 前缀：" + (char) prefix);
            };
        }

        private String line() throws IOException {
            ByteArrayOutputStream value = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = in.read();
                if (current < 0) throw new EOFException();
                if (previous == '\r' && current == '\n') break;
                if (previous >= 0) value.write(previous);
                previous = current;
            }
            return value.toString(StandardCharsets.UTF_8);
        }

        private byte[] bulk() throws IOException {
            int length = Integer.parseInt(line());
            if (length < 0) return null;
            byte[] value = in.readNBytes(length);
            if (value.length != length || in.read() != '\r' || in.read() != '\n') throw new EOFException();
            return value;
        }

        private List<Object> arrayResponse() throws IOException {
            int length = Integer.parseInt(line());
            if (length < 0) return List.of();
            List<Object> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) values.add(read());
            return values;
        }

        private static byte[] utf8(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }
}
