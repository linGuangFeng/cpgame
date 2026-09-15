package com.cpgame.luckynightmarket;

import java.math.BigDecimal;
import java.util.*;

/** Small strict JSON codec, independent of any web framework. */
public final class Json {
    private Json() {}
    public static Object parse(String text) { Parser p=new Parser(text); Object v=p.value(); p.ws(); if(p.i!=text.length()) throw p.error("Trailing input"); return v; }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object v) { if(!(v instanceof Map)) throw new IllegalArgumentException("Expected JSON object"); return (Map<String,Object>)v; }
    public static Map<String,Object> map(Object... values) { if(values.length%2!=0)throw new IllegalArgumentException(); Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2)m.put(values[i].toString(),values[i+1]);return m; }
    public static String stringify(Object value) { StringBuilder out=new StringBuilder(); write(out,value); return out.toString(); }
    private static void write(StringBuilder s,Object v) {
        if(v==null){s.append("null");return;}
        if(v instanceof String || v instanceof Character || v instanceof Enum<?>){quote(s,v.toString());return;}
        if(v instanceof Number n){if(n instanceof Double d&&!Double.isFinite(d)||n instanceof Float f&&!Float.isFinite(f))throw new IllegalArgumentException("Non-finite number");s.append(n instanceof BigDecimal b?b.toPlainString():n.toString());return;}
        if(v instanceof Boolean){s.append(v);return;}
        if(v instanceof Map<?,?> m){s.append('{');boolean first=true;for(var e:m.entrySet()){if(!first)s.append(',');first=false;quote(s,e.getKey().toString());s.append(':');write(s,e.getValue());}s.append('}');return;}
        if(v instanceof Iterable<?> list){s.append('[');boolean first=true;for(Object item:list){if(!first)s.append(',');first=false;write(s,item);}s.append(']');return;}
        throw new IllegalArgumentException("Unsupported JSON type "+v.getClass());
    }
    private static void quote(StringBuilder s,String v){s.append('"');for(int i=0;i<v.length();i++){char c=v.charAt(i);switch(c){case '"'->s.append("\\\"");case '\\'->s.append("\\\\");case '\n'->s.append("\\n");case '\r'->s.append("\\r");case '\t'->s.append("\\t");case '\b'->s.append("\\b");case '\f'->s.append("\\f");default->{if(c<32)s.append(String.format("\\u%04x",(int)c));else s.append(c);}}}s.append('"');}
    private static final class Parser {
        final String s;int i;int depth; Parser(String s){this.s=Objects.requireNonNull(s);}
        void ws(){while(i<s.length()&&" \t\r\n".indexOf(s.charAt(i))>=0)i++;}
        IllegalArgumentException error(String what){return new IllegalArgumentException(what+" at "+i);}
        Object value(){ws();if(++depth>128)throw error("JSON too deep");try{if(i>=s.length())throw error("Unexpected end");char c=s.charAt(i);if(c=='"')return string();if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<>();ws();if(take('}'))return m;do{ws();if(i>=s.length()||s.charAt(i)!='"')throw error("Expected key");String key=string();ws();expect(':');if(m.containsKey(key))throw error("Duplicate key");m.put(key,value());ws();if(take('}'))return m;expect(',');}while(true);}if(c=='['){i++;List<Object>a=new ArrayList<>();ws();if(take(']'))return a;do{a.add(value());ws();if(take(']'))return a;expect(',');}while(true);}for(String word:List.of("true","false","null")){if(s.startsWith(word,i)){i+=word.length();return word.equals("null")?null:word.equals("true");}}int start=i;if(take('-')&&i>=s.length())throw error("Expected number");if(take('0')){}else{if(i>=s.length()||s.charAt(i)<'1'||s.charAt(i)>'9')throw error("Expected number");while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}if(take('.')){int p=i;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(p==i)throw error("Expected fraction");}if(i<s.length()&&(s.charAt(i)=='e'||s.charAt(i)=='E')){i++;if(i<s.length()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;int p=i;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(p==i)throw error("Expected exponent");}return new BigDecimal(s.substring(start,i));}finally{depth--;}}
        boolean take(char c){if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        void expect(char c){if(!take(c))throw error("Expected "+c);}
        String string(){expect('"');StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c<32)throw error("Control character");if(c!='\\'){b.append(c);continue;}if(i>=s.length())throw error("Incomplete escape");switch(s.charAt(i++)){case '"'->b.append('"');case '\\'->b.append('\\');case '/'->b.append('/');case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(i+4>s.length())throw error("Incomplete Unicode escape");try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException e){throw error("Invalid Unicode escape");}i+=4;}default->throw error("Invalid escape");}}throw error("Unterminated string");}
    }
}
