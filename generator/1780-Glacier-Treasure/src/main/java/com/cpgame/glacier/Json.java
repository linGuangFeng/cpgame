package com.cpgame.glacier;
import java.math.BigDecimal;
import java.util.*;

/** Small dependency-free JSON codec. */
public final class Json {
    private final String s; private int p;
    private Json(String s){this.s=s;}
    public static Object parse(String s){var j=new Json(s);Object v=j.value();j.ws();if(j.p!=s.length())throw j.bad();return v;}
    private IllegalArgumentException bad(){return new IllegalArgumentException("Invalid JSON at "+p);}
    private void ws(){while(p<s.length() && " \t\r\n".indexOf(s.charAt(p))>=0)p++;}
    private char next(){if(p>=s.length())throw bad();return s.charAt(p++);}
    private boolean take(char c){ws();if(p<s.length() && s.charAt(p)==c){p++;return true;}return false;}
    private Object value(){
        ws();if(p>=s.length())throw bad();char c=s.charAt(p);
        if(c=='"')return string();
        if(c=='{'){
            p++;var m=new LinkedHashMap<String,Object>();if(take('}'))return m;
            do{ws();if(p>=s.length()||s.charAt(p)!='"')throw bad();String k=string();if(!take(':'))throw bad();
                if(m.containsKey(k))throw bad();m.put(k,value());}while(take(','));
            if(!take('}'))throw bad();return m;
        }
        if(c=='['){p++;var a=new ArrayList<Object>();if(take(']'))return a;do{a.add(value());}while(take(','));if(!take(']'))throw bad();return a;}
        for(String l:List.of("true","false","null"))if(s.startsWith(l,p)){p+=l.length();return l.equals("null")?null:l.equals("true");}
        int st=p;while(p<s.length() && "-+0123456789.eE".indexOf(s.charAt(p))>=0)p++;
        String n=s.substring(st,p);if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();
        return new BigDecimal(n);
    }
    private String string(){
        if(next()!='"')throw bad();var b=new StringBuilder();
        while(true){char c=next();if(c=='"')return b.toString();if(c<32)throw bad();
            if(c!='\\'){b.append(c);continue;}
            char e=next();switch(e){
                case '"','\\','/' -> b.append(e);
                case 'b' -> b.append('\b'); case 'f' -> b.append('\f'); case 'n' -> b.append('\n');
                case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                case 'u' -> {if(p+4>s.length())throw bad();try{b.append((char)Integer.parseInt(s.substring(p,p+4),16));}catch(NumberFormatException ex){throw bad();}p+=4;}
                default -> throw bad();
            }
        }
    }
    public static String stringify(Object o){
        if(o==null)return "null";
        if(o instanceof String x){var b=new StringBuilder("\"");for(char c:x.toCharArray())switch(c){
            case '"' -> b.append("\\\"");case '\\' -> b.append("\\\\");case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");case '\t' -> b.append("\\t");
            default -> {if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}
        }return b.append('"').toString();}
        if(o instanceof BigDecimal x) return x.stripTrailingZeros().toPlainString();
        if(o instanceof Number || o instanceof Boolean)return o.toString();
        if(o instanceof Map<?,?> m){var a=new ArrayList<String>();m.forEach((k,v)->a.add(stringify(k.toString())+":"+stringify(v)));return "{"+String.join(",",a)+"}";}
        if(o instanceof Collection<?> xs){var a=new ArrayList<String>();xs.forEach(v->a.add(stringify(v)));return "["+String.join(",",a)+"]";}
        throw new IllegalArgumentException("Unsupported JSON value "+o.getClass());
    }
}
