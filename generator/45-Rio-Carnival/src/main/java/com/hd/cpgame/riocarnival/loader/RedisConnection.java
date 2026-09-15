package com.hd.cpgame.riocarnival.loader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocketFactory;

/** 最小 RESP2 客户端；每批完整局在同一个 Redis MULTI/EXEC 内提交。 */
public final class RedisConnection implements AutoCloseable {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    private RedisConnection(Socket socket) throws IOException {
        this.socket = socket;
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
    }

    public static RedisConnection connect(String host,int port,int database,String username,String password)throws IOException {
        if(host==null||host.trim().isEmpty()||port<1||port>65535||database<0)throw new IllegalArgumentException("Rio45 Redis endpoint mismatch");
        Socket socket=new Socket();socket.connect(new InetSocketAddress(host,port),5000);socket.setSoTimeout(30000);
        RedisConnection c=new RedisConnection(socket);
        try {
            if(password!=null&&!password.isEmpty()) {
                if(username==null||username.isEmpty())c.command("AUTH",password);else c.command("AUTH",username,password);
            }
            c.command("SELECT","15");c.command("PING");return c;
        } catch(IOException ex){c.close();throw ex;}
    }

    static RedisConnection connect(GeneratorConfig config) throws IOException {
        Socket socket = config.ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(config.host, config.port), config.connectTimeoutMs);
        socket.setSoTimeout(config.socketTimeoutMs);
        RedisConnection connection = new RedisConnection(socket);
        try {
            if (!config.password.isEmpty()) {
                if (config.username.isEmpty()) connection.command("AUTH", config.password);
                else connection.command("AUTH", config.username, config.password);
            }
            if (config.database != 0) connection.command("SELECT", Integer.toString(config.database));
            Object pong = connection.command("PING");
            if (!"PONG".equals(pong)) throw new IOException("Redis PING 返回异常: " + pong);
            return connection;
        } catch (IOException ex) {
            connection.close();
            throw ex;
        }
    }

    public Object command(String... args) throws IOException {
        write(args);
        output.flush();
        return read();
    }

    void transaction(List<String[]> body) throws IOException {
        List<String[]> commands = new ArrayList<String[]>(body.size() + 2);
        commands.add(new String[]{"MULTI"});
        commands.addAll(body);
        commands.add(new String[]{"EXEC"});
        for (String[] command : commands) write(command);
        output.flush();
        List<Object> replies = new ArrayList<Object>(commands.size());
        for (int i = 0; i < commands.size(); i++) replies.add(read());
        Object exec = replies.get(replies.size() - 1);
        if (!(exec instanceof List) || ((List<?>) exec).size() != body.size())
            throw new IOException("Redis EXEC 返回数量与事务命令不一致");
    }

    private void write(String[] args) throws IOException {
        output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write('\r');
            output.write('\n');
        }
    }

    private Object read() throws IOException {
        int prefix = input.read();
        if (prefix < 0) throw new EOFException("Redis 已关闭连接");
        switch (prefix) {
            case '+': return line();
            case '-': throw new IOException("Redis 错误: " + line());
            case ':': return Long.parseLong(line());
            case '$': return bulk();
            case '*': return array();
            default: throw new IOException("非法 RESP 前缀: " + (char) prefix);
        }
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new EOFException();
            if (previous == '\r' && current == '\n') break;
            if (previous >= 0) bytes.write(previous);
            previous = current;
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private Object bulk() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        byte[] value = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(value, offset, length - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
        if (input.read() != '\r' || input.read() != '\n') throw new EOFException();
        return new String(value, StandardCharsets.UTF_8);
    }

    private Object array() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        List<Object> result = new ArrayList<Object>(length);
        for (int i = 0; i < length; i++) result.add(read());
        return result;
    }

    @Override public void close() throws IOException { socket.close(); }
}
