package com.cpgame.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CpgamePublicLabServerTest {
    @Test
    void rebaseKeepsRealGamePlayPath() {
        assertEquals("/play/16-Jungle-Fruit/index.html",
            CpgamePublicLabServer.rebasePlayPath("/play/16-Jungle-Fruit/index.html", "16-Jungle-Fruit"));
    }

    @Test
    void rebaseMapsSiblingPlayAssetsOntoTheOpenGame() {
        assertEquals("/play/16-Jungle-Fruit/0/versionconfig.js",
            CpgamePublicLabServer.rebasePlayPath("/play/0/versionconfig.js", "16-Jungle-Fruit"));
        assertEquals("/play/16-Jungle-Fruit/report.js",
            CpgamePublicLabServer.rebasePlayPath("/play/report.js", "16-Jungle-Fruit"));
        assertEquals("/play/16-Jungle-Fruit/asset/cocos2d-js-min.55e56.js",
            CpgamePublicLabServer.rebasePlayPath("/play/asset/cocos2d-js-min.55e56.js", "16-Jungle-Fruit"));
    }

    @Test
    void rebaseMapsAbsoluteApiAndRootAssetsOntoTheOpenGame() {
        assertEquals("/play/16-Jungle-Fruit/cp/api/v1/ping",
            CpgamePublicLabServer.rebasePlayPath("/cp/api/v1/ping", "16-Jungle-Fruit"));
        assertEquals("/play/16-Jungle-Fruit/0/logo.v3.1.png",
            CpgamePublicLabServer.rebasePlayPath("/0/logo.v3.1.png", "16-Jungle-Fruit"));
    }
}
