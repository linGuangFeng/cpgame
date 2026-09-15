package com.cpgame.luckynightmarket;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded RESP2 socket client. Transactions use batch(MULTI,...,EXEC). */
public final class RedisClient implements AutoCloseable {
    private final Socket socket; private final InputStream input; private final OutputStream output;
    public RedisClient(Properties p)throws IOException{this(p.getProperty("redis.host","18.234.101.161"),Integer.parseInt(p.getProperty("redis.port","8021")),p.getProperty("redis.password",""),Integer.parseInt(p.getProperty("redis.database",p.getProperty("redis.db","0"))), Integer.parseInt(p.getProperty("redis.connect-timeout-ms","5000")), Integer.parseInt(p.getProperty("redis.socket-timeout-ms","30000")));}
    public RedisClient(String host,int port,String password,int database)throws IOException {this(host,port,password,database,5000,30000);}
    public RedisClient(String host,int port,String password,int database,int connectTimeout,int socketTimeout)throws IOException {socket=new Socket();try{socket.connect(new InetSocketAddress(host,port),connectTimeout);socket.setSoTimeout(socketTimeout);socket.setTcpNoDelay(true);input=new BufferedInputStream(socket.getInputStream());output=new BufferedOutputStream(socket.getOutputStream());if(password!=null&&!password.isBlank())command("AUTH",password);command("SELECT",Integer.toString(database));}catch(IOException|RuntimeException e){socket.close();throw e;}}
    public synchronized Object command(String... args)throws IOException{write(Arrays.asList(args));output.flush();return read();}
    public synchronized List<Object> batch(List<List<String>> commands)throws IOException {for(List<String> c:commands)write(c);output.flush();List<Object> replies=new ArrayList<>();for(int i=0;i<commands.size();i++)replies.add(read());return replies;}
    private void write(List<String> args)throws IOException {ascii("*"+args.size()+"\r\n");for(String arg:args){byte[] bytes=arg.getBytes(StandardCharsets.UTF_8);ascii("$"+bytes.length+"\r\n");output.write(bytes);ascii("\r\n");}}
    private void ascii(String value)throws IOException{output.write(value.getBytes(StandardCharsets.US_ASCII));}
    private String line()throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=input.read())!=-1){if(c=='\r'){if(input.read()!='\n')throw new IOException("Invalid RESP line");return b.toString(StandardCharsets.UTF_8);}b.write(c);if(b.size()>1024*1024)throw new IOException("RESP line too large");}throw new EOFException("Redis connection closed");}
    private Object read()throws IOException {int tag=input.read();if(tag<0)throw new EOFException("Redis connection closed");return switch(tag){case '+'->line();case '-'->throw new IOException("Redis error: "+line());case ':'->Long.parseLong(line());case '$'->{int n=Integer.parseInt(line());if(n==-1)yield null;if(n<0||n>16*1024*1024)throw new IOException("Redis member too large");byte[] bytes=input.readNBytes(n);if(bytes.length!=n||input.read()!='\r'||input.read()!='\n')throw new EOFException("Incomplete RESP bulk");yield new String(bytes,StandardCharsets.UTF_8);}case '*'->{int n=Integer.parseInt(line());if(n==-1)yield null;if(n<0||n>2_000_000)throw new IOException("RESP array too large");List<Object>a=new ArrayList<>();for(int i=0;i<n;i++)a.add(read());yield a;}default->throw new IOException("Invalid RESP prefix");};}
    @Override public void close()throws IOException{socket.close();}
}
