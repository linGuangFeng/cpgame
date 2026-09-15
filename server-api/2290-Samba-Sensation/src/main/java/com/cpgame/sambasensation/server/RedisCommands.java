package com.cpgame.sambasensation.server;

import java.io.Closeable;
import java.io.IOException;

interface RedisCommands extends Closeable {
    Object command(String... args) throws IOException;
}
