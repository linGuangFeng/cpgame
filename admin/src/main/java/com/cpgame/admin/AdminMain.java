package com.cpgame.admin;

import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CountDownLatch;

public final class AdminMain {
    private AdminMain() { }

    public static void main(String[] args) throws Exception {
        AdminSettings settings = AdminSettings.fromArgs(args);
        ObjectMapper mapper = new ObjectMapper();
        CpgameLabService lab = new CpgameLabService(settings, mapper);
        CpgameDemoRuntimeService demo = new CpgameDemoRuntimeService(settings, mapper);
        lab.setDemoRuntime(demo);
        CpgameBetLogService betLog = new CpgameBetLogService(settings, mapper);
        CpgamePublicLabServer server = new CpgamePublicLabServer(lab, demo, betLog, settings, mapper);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "cpgame-admin-stop"));
        server.start();
        System.out.println("CPGame admin listening on http://" + displayHost(settings.getPublicAddress())
            + ":" + settings.getPublicPort());
        System.out.println(settings);
        new CountDownLatch(1).await();
    }

    private static String displayHost(String address) {
        return "0.0.0.0".equals(address) ? "127.0.0.1" : address;
    }
}
