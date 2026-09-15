package com.cpgame.glacier;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSocketFactory;

/** Generates complete rounds with SecureRandom and writes ASCII members to Redis db 15. */
public final class RedisDirectLoader {
    public static void main(String[] args) {
        try {
            Path config = Path.of(args.length==0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
            LoadSummary s = run(config);
            System.out.printf("LOAD_COMPLETE redisGameId=%d loss=%d win=%d special=%d batches=%d rulesHash=%s%n",
                s.redisGameId, s.loss, s.win, s.special, s.batches, GameRuleCore.RULES_HASH);
        } catch (Exception ex) {
            System.err.println("[失败] " + (ex.getMessage()==null ? ex.getClass().getSimpleName() : ex.getMessage()));
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public static LoadSummary run(Path configFile) throws Exception {
        GeneratorConfiguration c = GeneratorConfiguration.load(configFile);
        GenerationModel model = c.model();
        CompleteRoundFactory factory = new CompleteRoundFactory(model, c.maxConsecutiveWins, c.maxMarySpins,
            Math.addExact(c.lossConstructiveAttempts, c.lossFallbackSamples));
        System.out.printf("CONFIG loss=%d win=%d special=%d boost=%dx switchEvery=%d structures=%d%n",
            c.lossCount, c.winCount, c.specialCount, c.specialScatterBoost, c.entrySwitchEvery,
            c.paidInitialStructures.size());
        System.out.flush();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        SecureRandom random = new SecureRandom();
        var pending = new ArrayList<Member>();
        Counters counters = new Counters();
        fill(c, factory, codec, random, pending, counters, CompleteRoundFactory.Outcome.LOSS, c.lossCount, false);
        fill(c, factory, codec, random, pending, counters, CompleteRoundFactory.Outcome.WIN, c.winCount, false);
        fill(c, factory, codec, random, pending, counters, CompleteRoundFactory.Outcome.SPECIAL, c.specialCount, true);
        System.out.printf("GENERATION_COMPLETE members=%d writing redis%n", pending.size());
        System.out.flush();
        try (RedisConnection redis = RedisConnection.connect(c)) {
            var batch = new ArrayList<Member>(c.batchSize);
            for (Member m : pending) {
                batch.add(m);
                if (batch.size()>=c.batchSize) flush(redis, batch, c, counters);
            }
            flush(redis, batch, c, counters);
        }
        return new LoadSummary(c.redisGameId, counters.loss, counters.win, counters.special, counters.batches);
    }

    private static void fill(GeneratorConfiguration c, CompleteRoundFactory factory, CompleteRoundCodec codec,
                             SecureRandom random, List<Member> pending,
                             Counters counters, CompleteRoundFactory.Outcome target, int want, boolean special)
            throws Exception {
        Map<Integer,Integer> per = new HashMap<>();
        int got=0;
        if (target == CompleteRoundFactory.Outcome.LOSS) want = c.outputLimits.lossTarget(want);
        long attempts=0;
        int minWin = special ? c.maryMinWinMultiplier : c.normalMinWinMultiplier;
        int maxWin = special ? c.maryMaxWinMultiplier : c.normalMaxWinMultiplier;
        while (got<want) {
            LoaderLimits.checkAttempts(++attempts,want);
            CompleteRoundFactory.GeneratedRound generated;
            try { generated = factory.generateTarget(random, false, target); }
            catch (CompleteRoundFactory.RoundRejectedException ex) { counters.skipped++; continue; }
            if (generated.special()!=special) { counters.skipped++; continue; }
            int ratio = generated.ratio;
            if (special && ratio<=0) { counters.skipped++; continue; }
            if (!special && target==CompleteRoundFactory.Outcome.LOSS && ratio!=0) { counters.skipped++; continue; }
            if (!special && target==CompleteRoundFactory.Outcome.WIN && ratio<=0) { counters.skipped++; continue; }
            if (ratio<minWin || ratio>maxWin) { counters.skipped++; continue; }
            int have = per.getOrDefault(ratio, 0);
            String payload = codec.encode(generated.fact);
            if (payload.charAt(0)=='{' || payload.charAt(0)=='[') throw new IllegalStateException("JSON member");
            CompleteRoundFactory.verifyRatio(codec.decode(payload));
            per.put(ratio, have+1);
            pending.add(new Member(special, ratio, payload));
            got++;
            if (special) counters.special++; else if (ratio==0) counters.loss++; else counters.win++;
            if (got%10==0) {
                System.out.printf("GENERATED kind=%s %d/%d skipped=%d%n", target, got, want, counters.skipped);
                System.out.flush();
            }
        }
    }

    private static void flush(RedisConnection redis, List<Member> pending, GeneratorConfiguration c, Counters counters)
            throws IOException {
        if (pending.isEmpty()) return;
        for (Member m : pending) {
            String index = m.special ? RedisKeys.maryIndex(c.redisGameId) : RedisKeys.normalIndex(c.redisGameId);
            String list = m.special ? RedisKeys.maryList(c.redisGameId, m.ratio) : RedisKeys.normalList(c.redisGameId, m.ratio);
            redis.command("ZADD", index, Integer.toString(m.ratio), Integer.toString(m.ratio));
            redis.command("RPUSH", list, m.payload);
            int cap = m.special ? c.outputLimits.specialCap : c.maxMembersPerMultiplier;
            redis.command("LTRIM", list, "-"+cap, "-1");
        }
        counters.batches++;
        counters.loaded += pending.size();
        System.out.printf("BATCH_COMMITTED batch=%d members=%d loaded=%d%n", counters.batches, pending.size(), counters.loaded);
        System.out.flush();
        pending.clear();
    }

    public record LoadSummary(long redisGameId, int loss, int win, int special, int batches) {}
    private record Member(boolean special, int ratio, String payload) {}
    private static final class Counters { int batches, loaded, loss, win, special, skipped; }

    static final class RedisConnection implements AutoCloseable {
        private final Socket socket; private final InputStream in; private final OutputStream out;
        private RedisConnection(Socket s) throws IOException {
            socket=s; in=new BufferedInputStream(s.getInputStream()); out=new BufferedOutputStream(s.getOutputStream());
        }
        static RedisConnection connect(GeneratorConfiguration c) throws IOException {
            return connect(c.redisHost, c.redisPort, c.redisUsername, c.redisPassword, c.redisDatabase,
                c.redisSsl, c.connectTimeoutMs, c.socketTimeoutMs);
        }
        static RedisConnection connect(String host, int port, String username, String password, int database,
                                       boolean ssl, int connectTimeoutMs, int socketTimeoutMs) throws IOException {
            System.out.printf("REDIS_CONNECTING host=%s port=%d database=%d ssl=%s%n", host, port, database, ssl);
            Socket s = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            } catch (ConnectException refused) {
                throw new IOException("cannot connect Redis %s:%d".formatted(host, port), refused);
            }
            s.setSoTimeout(socketTimeoutMs);
            RedisConnection r=new RedisConnection(s);
            try {
                if (!password.isBlank()) {
                    if (username.isBlank()) r.command("AUTH", password); else r.command("AUTH", username, password);
                }
                r.command("SELECT", Integer.toString(database));
                if (!"PONG".equals(r.command("PING"))) throw new IOException("PING");
                System.out.printf("REDIS_CONNECTED host=%s port=%d database=%d%n", host, port, database);
                return r;
            } catch (Exception e) { r.close(); throw e instanceof IOException io?io:new IOException(e); }
        }
        Object command(String... args) throws IOException { write(args); out.flush(); return read(); }
        List<Object> pipeline(List<String[]> commands) throws IOException {
            for (String[] cmd:commands) write(cmd);
            out.flush();
            List<Object> replies=new ArrayList<>(commands.size());
            for (int i=0;i<commands.size();i++) replies.add(read());
            Object exec=replies.get(replies.size()-1);
            if (!(exec instanceof List<?> values) || values.size()!=commands.size()-2)
                throw new IOException("Redis EXEC mismatch");
            return replies;
        }
        private void write(String[] args) throws IOException {
            out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String a:args) {
                byte[] b=a.getBytes(StandardCharsets.UTF_8);
                out.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(b); out.write('\r'); out.write('\n');
            }
        }
        private Object read() throws IOException {
            int p=in.read(); if (p<0) throw new EOFException();
            return switch (p) {
                case '+' -> line();
                case '-' -> throw new IOException("Redis error: "+line());
                case ':' -> Long.parseLong(line());
                case '$' -> bulk();
                case '*' -> array();
                default -> throw new IOException("RESP");
            };
        }
        private String line() throws IOException {
            var b=new java.io.ByteArrayOutputStream(); int prev=-1;
            while (true) {
                int c=in.read(); if (c<0) throw new EOFException();
                if (prev=='\r' && c=='\n') break;
                if (prev>=0) b.write(prev); prev=c;
            }
            return b.toString(StandardCharsets.UTF_8);
        }
        private Object bulk() throws IOException {
            int n=Integer.parseInt(line()); if (n<0) return null;
            byte[] b=in.readNBytes(n);
            if (b.length!=n || in.read()!='\r' || in.read()!='\n') throw new EOFException();
            return new String(b, StandardCharsets.UTF_8);
        }
        private Object array() throws IOException {
            int n=Integer.parseInt(line()); if (n<0) return null;
            List<Object> v=new ArrayList<>(); for (int i=0;i<n;i++) v.add(read()); return v;
        }
        public void close() throws IOException { socket.close(); }
    }
}
