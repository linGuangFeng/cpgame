package com.cpgame.clubgoddess.loader;

import static org.junit.jupiter.api.Assertions.*;
import com.cpgame.clubgoddess.codec.MinimalRoundFactCodec;
import com.github.fppt.jedismock.RedisServer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 在独立 TCP 端口的纯 Java Redis 兼容实例上验证真实数据结构语义。 */
class IsolatedRedisIntegrationTest {
    @TempDir Path temp;

    @Test void isolatedRedisPersistsIndexesListsTrimsAndDecodableMembers() throws Exception {
        RedisServer redis = RedisServer.newRedisServer();
        redis.start();
        try {
            Path config = temp.resolve("generator.properties");
            Properties p = propertiesForExternalUse(redis.getBindPort(), 250, 3, 99_992_060L);
            try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) { p.store(writer, null); }
            RedisLoader.LoadSummary summary = RedisLoader.run(config);
            assertEquals(250, summary.normalGenerated());
            assertTrue(summary.normalWritten() > 3);

            try (RespClient client = new RespClient("127.0.0.1", redis.getBindPort())) {
                List<?> ratios = asList(client.command("ZRANGE", "PerKeyList_099992060", "0", "-1"));
                assertFalse(ratios.isEmpty());
                int decodedMembers = 0;
                MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
                for (Object ratio : ratios) {
                    String listKey = RedisLoader.normalList(99_992_060L, ratio.toString());
                    long length = (Long) client.command("LLEN", listKey);
                    assertTrue(length >= 1 && length <= 3, listKey + " 未按 LTRIM 保留 3 条以内");
                    List<?> members = asList(client.command("LRANGE", listKey, "0", "-1"));
                    assertEquals(length, members.size());
                    for (Object member : members) {
                        codec.verify(member.toString());
                        decodedMembers++;
                    }
                }
                assertTrue(decodedMembers > 0);
            }
        } finally {
            redis.stop();
        }
    }

    static Properties propertiesForExternalUse(int port, int normalCount, int capacity, long gameId) {
        Properties p = new Properties();
        p.setProperty("redis.host", "127.0.0.1"); p.setProperty("redis.port", Integer.toString(port));
        p.setProperty("redis.username", ""); p.setProperty("redis.password", "");
        p.setProperty("redis.database", "0"); p.setProperty("redis.ssl", "false");
        p.setProperty("redis.connect-timeout-ms", "5000"); p.setProperty("redis.socket-timeout-ms", "30000");
        p.setProperty("redis.game-id", Long.toString(gameId)); p.setProperty("generation.normal-count", Integer.toString(normalCount));
        p.setProperty("generation.special-count", "0"); p.setProperty("generation.batch-size", "17");
        p.setProperty("generation.max-members-per-multiplier", Integer.toString(capacity));
        p.setProperty("generation.max-consecutive-wins", "10");
        p.setProperty("generation.normal-max-win-multiplier", "20000");
        p.setProperty("generation.special-max-win-multiplier", "20000");
        return p;
    }

    private static List<?> asList(Object value) { assertInstanceOf(List.class, value); return (List<?>) value; }

    private static final class RespClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedInputStream in;
        private final BufferedOutputStream out;
        RespClient(String host, int port) throws Exception {
            socket = new Socket(host, port); in = new BufferedInputStream(socket.getInputStream()); out = new BufferedOutputStream(socket.getOutputStream());
        }
        Object command(String... args) throws Exception {
            out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));
            for(String arg:args){byte[] bytes=arg.getBytes(StandardCharsets.UTF_8);out.write(("$"+bytes.length+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.write('\r');out.write('\n');}
            out.flush(); return read();
        }
        Object read() throws Exception {
            int prefix=in.read();if(prefix<0)throw new EOFException();
            return switch(prefix){case '+'->line();case ':'->Long.parseLong(line());case '$'->bulk();case '*'->array();case '-'->throw new IllegalStateException("Redis error: "+line());default->throw new IllegalStateException("RESP prefix "+prefix);};
        }
        String line() throws Exception {var bytes=new java.io.ByteArrayOutputStream();int previous=-1;while(true){int current=in.read();if(current<0)throw new EOFException();if(previous=='\r'&&current=='\n')break;if(previous>=0)bytes.write(previous);previous=current;}return bytes.toString(StandardCharsets.UTF_8);}
        Object bulk() throws Exception {int length=Integer.parseInt(line());if(length<0)return null;byte[] value=in.readNBytes(length);if(value.length!=length||in.read()!='\r'||in.read()!='\n')throw new EOFException();return new String(value,StandardCharsets.UTF_8);}
        Object array() throws Exception {int length=Integer.parseInt(line());if(length<0)return null;List<Object> result=new ArrayList<>(length);for(int i=0;i<length;i++)result.add(read());return result;}
        @Override public void close() throws Exception { socket.close(); }
    }
}
