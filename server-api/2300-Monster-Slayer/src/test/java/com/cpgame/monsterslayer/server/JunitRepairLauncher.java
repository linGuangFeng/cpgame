package com.cpgame.monsterslayer.server;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import java.io.PrintWriter;
public final class JunitRepairLauncher {
    public static void main(String[] args) {
        var listener=new SummaryGeneratingListener();
        var request=LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass("com.cpgame.monsterslayer.GenerationValidationTest"),
                       DiscoverySelectors.selectClass("com.cpgame.monsterslayer.server.RedisRoundStoreEmptyTest"),
                       DiscoverySelectors.selectClass("com.cpgame.monsterslayer.server.ControllerContractTest")).build();
        LauncherFactory.create().execute(request,listener);
        var summary=listener.getSummary();summary.printTo(new PrintWriter(System.out,true));
        summary.printFailuresTo(new PrintWriter(System.out,true));
        if(summary.getTestsFoundCount()<8||summary.getTestsFailedCount()!=0)System.exit(1);
    }
}
