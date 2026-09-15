package com.cpgame.monsterslayer.server;
import java.io.Closeable;import java.io.IOException;
interface RedisCommands extends Closeable { Object command(String...args)throws IOException; }
