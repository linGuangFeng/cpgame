package com.cpgame.curupira.api;

import java.io.Closeable;
import java.io.IOException;

interface RedisCommands extends Closeable {
    Object command(String... args) throws IOException;
}
