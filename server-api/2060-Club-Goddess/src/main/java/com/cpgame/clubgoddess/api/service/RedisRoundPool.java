package com.cpgame.clubgoddess.api.service;

import com.cpgame.clubgoddess.codec.MinimalRoundFactCodec;
import com.cpgame.clubgoddess.core.GameModels.RoundBundle;
import com.cpgame.clubgoddess.core.ResultUtil;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Redis-only complete-round source. Empty or invalid pools fail closed. */
@Component
public final class RedisRoundPool {
    @Value("${redis.host:18.234.101.161}") String host;
    @Value("${redis.port:8021}") int port;
    @Value("${redis.database:0}") int database;
    @Value("${redis.game-id:8002060}") long gameId;
    @Value("${redis.password:}") String password;
    @Value("${redis.username:}") String username;
    private final SecureRandom random=new SecureRandom();
    private final MinimalRoundFactCodec codec=new MinimalRoundFactCodec();

    public Claimed claim(String requestedKind)throws IOException{
        String kind=requestedKind==null?"":requestedKind.toLowerCase(Locale.ROOT);
        if(kind.isBlank())kind=!random.nextBoolean()?"loss":(random.nextInt(5)==0?"special":"win");
        boolean special="special".equals(kind);boolean loss="loss".equals(kind);
        try(Redis c=Redis.connect(host,port,database,username,password)){
            String index=special?specialIndex():normalIndex();List<Integer>ratios=ratios(c,index,special,loss);
            while(!ratios.isEmpty()){
                int ratio=ratios.remove(random.nextInt(ratios.size()));String list=special?specialList(ratio):normalList(ratio);
                Object length=c.command("LLEN",list);long len=length instanceof Long n?n:Long.parseLong(String.valueOf(length));
                if(len<=0)continue;
                Object member=c.command("LINDEX",list,Integer.toString(random.nextInt((int)Math.min(len,Integer.MAX_VALUE))));
                if(member==null)continue;String ascii=member.toString();RoundBundle round=codec.verify(ascii);
                int actual=ResultUtil.integerMultiplier(round);if(actual!=ratio)throw new IllegalStateException("Redis multiplier mismatch");
                boolean isSpecial=round.deliveries().size()>1;if(isSpecial!=special||(!special&&loss!=(actual==0)))throw new IllegalStateException("Redis pool classification mismatch");
                return new Claimed(ascii,special,ratio);
            }
        }
        throw new IllegalStateException("requested Redis cache pool is empty: "+kind);
    }
    private List<Integer>ratios(Redis c,String index,boolean special,boolean loss)throws IOException{Object raw=c.command("ZRANGE",index,"0","-1");List<Integer>out=new ArrayList<>();if(raw instanceof List<?>list)for(Object v:list){int n=Integer.parseInt(v.toString());if(special?n>0:(loss?n==0:n>0))out.add(n);}return out;}
    private String normalIndex(){return String.format("PerKeyList_%09d",gameId);}private String specialIndex(){return String.format("MaryKeyList_%09d",gameId);}
    private String normalList(int ratio){return String.format("BetLog:0%08d:%s",gameId,token(ratio));}private String specialList(int ratio){return String.format("MaryLog:%09d:%s",gameId,token(ratio));}private String token(int n){String s=Integer.toString(n);return "0".repeat(Math.max(0,6-s.length()))+s;}
    public record Claimed(String member,boolean special,int integerMultiplier){}

    static final class Redis implements AutoCloseable{
        final Socket socket;final InputStream in;final OutputStream out;
        Redis(Socket s)throws IOException{socket=s;in=new BufferedInputStream(s.getInputStream());out=new BufferedOutputStream(s.getOutputStream());}
        static Redis connect(String host,int port,int db,String user,String pass)throws IOException{Socket s=new Socket();s.connect(new InetSocketAddress(host,port),5000);s.setSoTimeout(30000);Redis r=new Redis(s);try{if(!pass.isBlank()){if(user.isBlank())r.command("AUTH",pass);else r.command("AUTH",user,pass);}r.command("SELECT",Integer.toString(db));if(!"PONG".equals(r.command("PING")))throw new IOException("Redis PING failed");return r;}catch(Exception e){r.close();throw e instanceof IOException io?io:new IOException(e);}}
        Object command(String...args)throws IOException{out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));for(String a:args){byte[]b=a.getBytes(StandardCharsets.UTF_8);out.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.write('\r');out.write('\n');}out.flush();return read();}
        Object read()throws IOException{int p=in.read();if(p<0)throw new EOFException();return switch(p){case '+'->line();case '-'->throw new IOException("Redis error: "+line());case ':'->Long.parseLong(line());case '$'->bulk();case '*'->array();default->throw new IOException("RESP");};}
        String line()throws IOException{var b=new ByteArrayOutputStream();int prev=-1;while(true){int c=in.read();if(c<0)throw new EOFException();if(prev=='\r'&&c=='\n')break;if(prev>=0)b.write(prev);prev=c;}return b.toString(StandardCharsets.UTF_8);}
        Object bulk()throws IOException{int n=Integer.parseInt(line());if(n<0)return null;byte[]b=in.readNBytes(n);if(b.length!=n||in.read()!='\r'||in.read()!='\n')throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
        Object array()throws IOException{int n=Integer.parseInt(line());if(n<0)return null;List<Object>v=new ArrayList<>();for(int i=0;i<n;i++)v.add(read());return v;}public void close()throws IOException{socket.close();}
    }
}
