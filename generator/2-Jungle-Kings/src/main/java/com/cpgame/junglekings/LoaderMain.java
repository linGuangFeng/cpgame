package com.cpgame.junglekings;

/** Dist JAR entry. Prints the realtime catalog. Does not write Redis. */
public final class LoaderMain {
    private LoaderMain() { }

    public static void main(String[] args) {
        JungleKingsCatalogCli.main(args);
    }
}
