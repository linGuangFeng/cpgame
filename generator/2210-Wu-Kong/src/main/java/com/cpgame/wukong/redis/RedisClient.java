package com.cpgame.wukong.redis;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocketFactory;

/** 最小 RESP2 客户端；连接失败只报告网络/配置，不提供本地兜底。 */
public final class RedisClient implements AutoCloseable {
    private final Socket socket;private final InputStream in;private final OutputStream out;
    private RedisClient(Socket s)throws IOException{socket=s;in=new BufferedInputStream(s.getInputStream());out=new BufferedOutputStream(s.getOutputStream());}
    public static RedisClient connect(String host,int port,String user,String password,int db,boolean ssl,int connectMs,int socketMs)throws IOException{
        Socket s=ssl?SSLSocketFactory.getDefault().createSocket():new Socket();try{s.connect(new InetSocketAddress(host,port),connectMs);}catch(IOException e){throw new IOException("无法连接 Redis "+host+":"+port+"，请检查网络和配置",e);}s.setSoTimeout(socketMs);RedisClient c=new RedisClient(s);
        try{if(password!=null&&!password.isBlank()){if(user==null||user.isBlank())c.command("AUTH",password);else c.command("AUTH",user,password);}c.command("SELECT",Integer.toString(db));if(!"PONG".equals(c.command("PING")))throw new IOException("Redis PING failed");return c;}catch(Exception e){c.close();if(e instanceof IOException io)throw io;throw new IOException(e);}
    }
    public synchronized Object command(String...args)throws IOException{out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));for(String arg:args){byte[] b=arg.getBytes(StandardCharsets.UTF_8);out.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.write('\r');out.write('\n');}out.flush();return read();}
    private Object read()throws IOException{int p=in.read();if(p<0)throw new EOFException();return switch(p){case'+'->line();case'-'->throw new IOException("Redis error: "+line());case':'->Long.parseLong(line());case'$'->bulk();case'*'->array();default->throw new IOException("bad RESP prefix");};}
    private String line()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int prev=-1;while(true){int cur=in.read();if(cur<0)throw new EOFException();if(prev=='\r'&&cur=='\n')break;if(prev>=0)b.write(prev);prev=cur;}return b.toString(StandardCharsets.UTF_8);}
    private Object bulk()throws IOException{int n=Integer.parseInt(line());if(n<0)return null;byte[] b=in.readNBytes(n);if(b.length!=n||in.read()!='\r'||in.read()!='\n')throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
    private Object array()throws IOException{int n=Integer.parseInt(line());if(n<0)return null;List<Object> values=new ArrayList<>(n);for(int i=0;i<n;i++)values.add(read());return values;}
    public void close()throws IOException{socket.close();}
}
