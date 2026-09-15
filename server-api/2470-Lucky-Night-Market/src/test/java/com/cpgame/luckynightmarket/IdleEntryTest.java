package com.cpgame.luckynightmarket;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** No Redis credentials are supplied: entry must use only the canonical idle model. */
public final class IdleEntryTest {
    public static void main(String[] args) throws Exception {
        var app = new ControllerMain(new Properties(), Path.of("."), 52470);
        var sessionType = Class.forName(ControllerMain.class.getName() + "$Session");
        var ctor = sessionType.getDeclaredConstructor(String.class, BigDecimal.class);
        ctor.setAccessible(true);
        var session = ctor.newInstance("idle-test", new BigDecimal("10000"));
        var init = ControllerMain.class.getDeclaredMethod("initRoom", sessionType);
        init.setAccessible(true);
        for (int i = 0; i < 10; i++) {
            var data = (Map<?, ?>) init.invoke(app, session);
            for (String key : new String[]{"tw", "cg"})
                if (((BigDecimal) data.get(key)).signum() != 0) throw new AssertionError(key);
            if (((BigDecimal) data.get("eg")).compareTo(new BigDecimal("10000")) != 0)
                throw new AssertionError("balance changed");
        }
        for (String name : new String[]{"active", "rounds", "history"}) {
            var field = sessionType.getDeclaredField(name);field.setAccessible(true);
            var value = field.get(session);
            if (name.equals("active") ? value != null : name.equals("rounds") ? !value.equals(0) : !((java.util.Collection<?>) value).isEmpty())
                throw new AssertionError("entry changes " + name);
        }
        System.out.println("PASS: entry without Redis, no charge, win, active round or history");
    }
}
