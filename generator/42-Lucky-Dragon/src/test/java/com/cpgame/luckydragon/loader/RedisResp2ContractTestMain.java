package com.cpgame.luckydragon.loader;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Repeatable local RESP2 contract test; deliberately not reported as a real Redis test. */
public final class RedisResp2ContractTestMain {
    public static void main(String[] args) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            FakeRedis fake = new FakeRedis(listener);
            Thread server = new Thread(fake, "gid42-resp2-contract");
            server.start();
            LoaderMain.LoaderConfig config = new LoaderMain.LoaderConfig("127.0.0.1", listener.getLocalPort(), "", "", 15, false,
                2000, 2000, 42, 1, 1, 1, 300, 10, 111, 9999,
                Map.of("H0", 1468, "H1", 642, "H2", 670, "H3", 412, "WILD", 198),
                Map.of(3, 87, 5, 43, 9, 35),
                com.cpgame.luckydragon.core.RandomRoundGenerator.DEFAULT_JOINT_MODEL);
            try (LoaderMain.RedisConnection redis = LoaderMain.RedisConnection.connect(config)) {
                redis.exec(List.of(new String[]{"MULTI"}, new String[]{"ZADD", "PerKeyList_000000042", "21", "21"},
                    new String[]{"RPUSH", "BetLog:000000042:000021", "v2|bs=0.5|bl=1|rpx=0|s=111|rk=42-test|di=0|tm=1"},
                    new String[]{"LTRIM", "BetLog:000000042:000021", "-300", "-1"}, new String[]{"EXEC"}));
                redis.command("RPUSH", "claim", "member-once");
                if (!Long.valueOf(1).equals(redis.command("LLEN", "claim"))) throw new AssertionError("LLEN");
                if (!"member-once".equals(redis.command("LPOP", "claim"))) throw new AssertionError("LPOP");
                if (!Long.valueOf(0).equals(redis.command("LLEN", "claim"))) throw new AssertionError("claim was duplicated");
                if (redis.command("BLPOP", "claim", "1") != null) throw new AssertionError("BLPOP should be empty");
            }
            server.join(2000);
            if (fake.failure != null) throw new AssertionError("fake RESP2 failure", fake.failure);
            if (!fake.atomicQueueSeen || !fake.singleClaimSeen) throw new AssertionError("queue contract not observed");
        }
        System.out.println("RedisResp2ContractTestMain PASS MULTI/EXEC RPUSH+LTRIM LPOP/BLPOP/LLEN single-claim");
    }

    private static final class FakeRedis implements Runnable {
        private final ServerSocket listener;
        private final Deque<String> claim = new ArrayDeque<>();
        volatile Throwable failure;
        volatile boolean atomicQueueSeen, singleClaimSeen;
        FakeRedis(ServerSocket listener) { this.listener = listener; }
        @Override public void run() {
            try (Socket socket = listener.accept()) {
                InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
                boolean multi = false; int queued = 0;
                for (;;) {
                    String[] command = readCommand(in); if (command == null) break;
                    String op = command[0].toUpperCase(Locale.ROOT);
                    if (multi && !"EXEC".equals(op)) { queued++; if ("RPUSH".equals(op) && command[1].contains("BetLog:")) atomicQueueSeen = true; write(out, "+QUEUED\r\n"); continue; }
                    switch (op) {
                        case "PING", "SELECT" -> write(out, "+" + (op.equals("PING") ? "PONG" : "OK") + "\r\n");
                        case "MULTI" -> { multi = true; queued = 0; write(out, "+OK\r\n"); }
                        case "EXEC" -> { write(out, "*" + queued + "\r\n"); for (int i=0;i<queued;i++) write(out, "+OK\r\n"); multi = false; }
                        case "RPUSH" -> { claim.addLast(command[2]); write(out, ":" + claim.size() + "\r\n"); }
                        case "LLEN" -> write(out, ":" + claim.size() + "\r\n");
                        case "LPOP" -> { String value = claim.pollFirst(); if (value == null) write(out, "$-1\r\n"); else write(out, "$"+value.length()+"\r\n"+value+"\r\n"); if (value != null) singleClaimSeen = true; }
                        case "BLPOP" -> write(out, "*-1\r\n");
                        case "ZADD", "LTRIM" -> write(out, ":1\r\n");
                        default -> throw new IOException("unexpected command " + op);
                    }
                    out.flush();
                }
            } catch (Throwable error) { failure = error; }
        }
        private static void write(OutputStream out, String text) throws IOException { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        private static String[] readCommand(InputStream in) throws IOException {
            String header = readLine(in); if (header == null) return null; if (!header.startsWith("*")) throw new IOException("RESP array expected");
            int count = Integer.parseInt(header.substring(1)); String[] result = new String[count];
            for (int i=0;i<count;i++) { String len = readLine(in); int n = Integer.parseInt(len.substring(1)); byte[] b = in.readNBytes(n); in.read(); in.read(); result[i] = new String(b, StandardCharsets.UTF_8); }
            return result;
        }
        private static String readLine(InputStream in) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); int p=-1; for (;;) { int c=in.read(); if(c<0)return b.size()==0?null:b.toString(StandardCharsets.UTF_8); if(p=='\r'&&c=='\n')return b.toString(StandardCharsets.UTF_8); if(p>=0)b.write(p); p=c; } }
    }
}
