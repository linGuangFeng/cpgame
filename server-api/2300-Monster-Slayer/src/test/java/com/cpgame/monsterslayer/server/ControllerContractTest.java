package com.cpgame.monsterslayer.server;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
final class ControllerContractTest {
    @Test void acceptsOnlyInjectedContractPortRange() throws Exception {
        assertEquals(50000,port(Map.of("port","50000")));
        assertEquals(59999,port(Map.of("port","59999")));
        assertThrows(InvocationTargetException.class,()->port(Map.of("port","49999")));
        assertThrows(InvocationTargetException.class,()->port(Map.of("port","60000")));
    }
    @Test void acceptsSplitAndEqualsPortFlags() throws Exception {
        Method parse=MonsterSlayerController.class.getDeclaredMethod("options",String[].class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String,String> split=(Map<String,String>)parse.invoke(null,(Object)new String[]{"--config","x.properties","--port","52300","--publish","pub"});
        assertEquals(52300,port(split));
        @SuppressWarnings("unchecked")
        Map<String,String> equals=(Map<String,String>)parse.invoke(null,(Object)new String[]{"--port=52301","--config=x.properties"});
        assertEquals(52301,port(equals));
    }
    static int port(Map<String,String> options) throws Exception {
        Method m=MonsterSlayerController.class.getDeclaredMethod("requiredPort",Map.class);
        m.setAccessible(true);return (Integer)m.invoke(null,options);
    }
    public static void main(String[]args)throws Exception { System.out.println("INJECTED_PORT="+port(Map.of())); }
}
