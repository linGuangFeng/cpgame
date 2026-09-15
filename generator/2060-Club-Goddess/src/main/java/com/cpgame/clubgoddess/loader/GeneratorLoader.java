package com.cpgame.clubgoddess.loader;

/** Backward-compatible entry name; the executable manifest uses RedisLoader directly. */
@Deprecated(forRemoval = false)
public final class GeneratorLoader {
    private GeneratorLoader() {}

    public static void main(String[] args) throws Exception {
        RedisLoader.main(args);
    }
}
