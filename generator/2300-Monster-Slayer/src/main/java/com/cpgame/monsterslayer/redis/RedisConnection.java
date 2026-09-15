package com.cpgame.monsterslayer.redis;

import com.cpgame.monsterslayer.generator.GeneratorConfig;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small RESP2 client; connection and command failures are never hidden. */
public final class RedisConnection implements AutoCloseable {
    private final Socket socket; private final BufferedInputStream in; private final BufferedOutputStream out;
    private RedisConnection(Socket socket)throws IOException{this.socket=socket;in=new BufferedInputStream(socket.getInputStream());out=new BufferedOutputStream(socket.getOutputStream());}
    public static RedisConnection connect(GeneratorConfig c)throws IOException{
        Socket s=c.redisSsl?SSLSocketFactory.getDefault().createSocket():new Socket(); s.connect(new InetSocketAddress(c.redisHost,c.redisPort),c.connectTimeoutMs);s.setSoTimeout(c.socketTimeoutMs);RedisConnection r=new RedisConnection(s);
        try{if(!c.redisPassword.isBlank()&&c.redisUsername.isBlank())r.command("AUTH",c.redisPassword);if(!c.redisPassword.isBlank()&&!c.redisUsername.isBlank())r.command("AUTH",c.redisUsername,c.redisPassword);r.command("SELECT",Integer.toString(c.redisDatabase));if(!"PONG".equals(r.command("PING")))throw new IOException("Redis PING did not return PONG");return r;}catch(Exception e){r.close();throw e;}
    }
    public synchronized Object command(String...args)throws IOException{write(args);out.flush();return read();}
    public synchronized List<Object> transaction(List<String[]> commands) throws IOException {
        write(new String[]{"MULTI"});
        // Bound unread QUEUED replies while keeping a usual 100-round batch in one round trip.
        for (int start = 0; start < commands.size(); start += 512) {
            int end = Math.min(commands.size(), start + 512);
            for (int i = start; i < end; i++) write(commands.get(i));
            if (end == commands.size()) write(new String[]{"EXEC"});
            out.flush();
            if (start == 0 && !"OK".equals(read())) throw new IOException("MULTI failed");
            for (int i = start; i < end; i++) {
                if (!"QUEUED".equals(read())) throw new IOException("command not queued");
            }
        }
        if (commands.isEmpty()) {
            write(new String[]{"EXEC"});
            out.flush();
            if (!"OK".equals(read())) throw new IOException("MULTI failed");
        }
        Object result = read();
        if (!(result instanceof List<?> list) || list.size() != commands.size())
            throw new IOException("EXEC response mismatch");
        return new ArrayList<>(list);
    }
    private void write(String[]args)throws IOException{out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));for(String a:args){byte[]b=a.getBytes(StandardCharsets.UTF_8);out.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.write('\r');out.write('\n');}}
    private Object read()throws IOException{int p=in.read();if(p<0)throw new EOFException("Redis closed connection");return switch(p){case '+'->line();case '-'->throw new IOException("Redis error: "+line());case ':'->Long.parseLong(line());case '$'->bulk();case '*'->array();default->throw new IOException("invalid RESP prefix");};}
    private String line()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int prev=-1;while(true){int cur=in.read();if(cur<0)throw new EOFException();if(prev=='\r'&&cur=='\n')return b.toString(StandardCharsets.UTF_8);if(prev>=0)b.write(prev);prev=cur;}}
    private Object bulk()throws IOException{int n=Integer.parseInt(line());if(n<0)return null;byte[]b=in.readNBytes(n);if(b.length!=n||in.read()!='\r'||in.read()!='\n')throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
    private Object array()throws IOException{int n=Integer.parseInt(line());if(n<0)return null;List<Object>v=new ArrayList<>();for(int i=0;i<n;i++)v.add(read());return v;}
    @Override public void close()throws IOException{socket.close();}
}
