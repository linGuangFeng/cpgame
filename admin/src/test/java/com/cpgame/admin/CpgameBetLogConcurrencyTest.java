package com.cpgame.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class CpgameBetLogConcurrencyTest {
    @TempDir Path root;

    // Real, idle child processes exercise the limit without connecting to Redis or generating data.
    public static class IdleLoader {
        public static void main(String[] args) throws Exception { Thread.sleep(120_000); }
    }

    @Test void admitsFifteenTasksAndReusesAReleasedSlot() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, IdleLoader.class.getName());
        String resource = IdleLoader.class.getName().replace('.', '/') + ".class";
        for (int i = 1; i <= 16; i++) {
            Path dist = Files.createDirectories(root.resolve("generator/" + i + "-test/dist"));
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(dist.resolve("loader.jar")), manifest);
                 var input = IdleLoader.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                jar.putNextEntry(new JarEntry(resource));
                input.transferTo(jar);
                jar.closeEntry();
            }
        }
        var settings = new AdminSettings(root, root.resolve("runtime"), "127.0.0.1", 8000);
        var service = new CpgameBetLogService(settings, new ObjectMapper());
        try {
            for (int i = 1; i <= 15; i++) assertEquals("RUNNING", service.run(i + "-test").state());
            assertEquals(15, service.runningCount());
            assertEquals("RUNNING", service.run("1-test").state());
            assertEquals(15, service.runningCount());
            var error = assertThrows(IllegalStateException.class, () -> service.run("16-test"));
            assertTrue(error.getMessage().contains("15"));
            service.interrupt("1-test");
            assertEquals("RUNNING", service.run("16-test").state());
            assertEquals(15, service.runningCount());
        } finally {
            service.interruptAll();
        }
    }
}
