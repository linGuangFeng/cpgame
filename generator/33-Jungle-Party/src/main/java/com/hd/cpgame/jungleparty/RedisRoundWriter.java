package com.hd.cpgame.jungleparty;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocketFactory;

/** Minimal RESP2 client: every list member is one complete Round. */
public final class RedisRoundWriter implements AutoCloseable {
    private int batchSize=1, pending;
    public void batchSize(int size){if(size<1)throw new IllegalArgumentException("batch size");batchSize=size;}
    public void flush() throws IOException {if(pending>0){Object reply=command("EXEC");if(!(reply instanceof List<?> values)||values.size()!=pending*3)throw new IOException("invalid EXEC reply");pending=0;}}
    private final Socket socket;
    private final BufferedInputStream input;
    private final BufferedOutputStream output;
    public RedisRoundWriter(String host, int port, int connectTimeoutMs, int socketTimeoutMs, boolean ssl) throws IOException {
        socket = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs); socket.setSoTimeout(socketTimeoutMs);
        input = new BufferedInputStream(socket.getInputStream()); output = new BufferedOutputStream(socket.getOutputStream());
    }
    public RedisRoundWriter(String host, int port, int timeoutMs) throws IOException { this(host, port, timeoutMs, timeoutMs, false); }
    public void select(int database) throws IOException { command("SELECT", Integer.toString(database)); }
    public void auth(String password) throws IOException { if (password != null && !password.isBlank()) command("AUTH", password); }
    public void auth(String username, String password) throws IOException {
        if (password == null || password.isBlank()) return;
        if (username == null || username.isBlank()) auth(password); else command("AUTH", username, password);
    }
    public void appendBounded(String index, String listKey, int multiplier, String member, int maxLength) throws IOException {
        if(pending==0)command("MULTI"); command("ZADD", index, Integer.toString(multiplier), Integer.toString(multiplier));
        command("RPUSH", listKey, member); command("LTRIM", listKey, Integer.toString(-maxLength), "-1");
        if(++pending>=batchSize)flush();
    }
    public String lpop(String key) throws IOException { Object value = command("LPOP", key); return value == null ? null : requireString(value); }
    public long llen(String key) throws IOException { Object value = command("LLEN", key); if (!(value instanceof Long number)) throw new IOException("invalid LLEN reply"); return number; }
    public List<String> zrange(String key) throws IOException {
        Object value = command("ZRANGE", key, "0", "-1");
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>(); for (Object item : list) result.add(requireString(item)); return result;
    }
    public Object command(String... parts) throws IOException {
        write("*" + parts.length + "\r\n");
        for (String part : parts) { byte[] bytes = part.getBytes(StandardCharsets.UTF_8); write("$" + bytes.length + "\r\n"); output.write(bytes); write("\r\n"); }
        output.flush(); return readResponse();
    }
    private Object readResponse() throws IOException {
        int type = input.read(); if (type < 0) throw new EOFException("Redis closed connection"); String line = readLine();
        return switch (type) { case '+' -> line; case '-' -> throw new IOException("Redis error: " + line); case ':' -> Long.parseLong(line);
            case '$' -> readBulk(Integer.parseInt(line)); case '*' -> readArray(Integer.parseInt(line)); default -> throw new IOException("unknown RESP type"); };
    }
    private String readBulk(int length) throws IOException { if (length == -1) return null; byte[] bytes = input.readNBytes(length); if (bytes.length != length) throw new EOFException(); expectCrLf(); return new String(bytes, StandardCharsets.UTF_8); }
    private List<Object> readArray(int count) throws IOException { if (count == -1) return null; List<Object> values = new ArrayList<>(); for (int i=0;i<count;i++) values.add(readResponse()); return values; }
    private String readLine() throws IOException { StringBuilder value = new StringBuilder(); for (;;) { int c=input.read(); if(c<0)throw new EOFException(); if(c=='\r'){if(input.read()!='\n')throw new IOException("bad CRLF"); return value.toString();} value.append((char)c); } }
    private void expectCrLf() throws IOException { if(input.read()!='\r'||input.read()!='\n')throw new IOException("bad bulk terminator"); }
    private static String requireString(Object value) throws IOException { if(!(value instanceof String text))throw new IOException("expected bulk string"); return text; }
    private void write(String value) throws IOException { output.write(value.getBytes(StandardCharsets.US_ASCII)); }
    @Override public void close() throws IOException { socket.close(); }
}
