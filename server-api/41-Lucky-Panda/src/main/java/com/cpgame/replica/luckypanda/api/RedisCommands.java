package com.cpgame.replica.luckypanda.api;

import java.io.IOException;

/** Minimal RESP surface used by Demo claim. Never deals a board. */
interface RedisCommands extends AutoCloseable {
    Object command(String... args) throws IOException;

    @Override
    void close() throws IOException;
}
