package com.cpgame.luckydragon.api;

import com.lgf.agentai.service.CpgameSharedHttpServerProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.CountDownLatch;

/** Local acceptance managed-Host harness used when the platform manager is not running. */
public final class SharedHostMachineTestMain {
    private SharedHostMachineTestMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        Path config = root.resolve("server-api/42-Lucky-Dragon/dist/server.properties");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 18743;
        Mount mount = new Mount();
        Path secondJar = args.length > 2 ? root.resolve(args[2]).normalize() : null;
        Path secondConfig = args.length > 3 ? root.resolve(args[3]).normalize() : null;
        Mount secondMount = secondJar == null ? null : new Mount();
        System.setProperty("com.sun.net.httpserver.HttpServerProvider", CpgameSharedHttpServerProvider.class.getName());
        HttpServer outer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 32);
        outer.createContext("/cp/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            (secondMount != null && query != null && query.matches(".*(?:^|&)gid=43(?:&|$).*")
                ? secondMount : mount).dispatch(exchange);
        });
        outer.createContext("/__controller/status", mount::dispatch);
        outer.createContext("/play/", exchange -> serveStatic(exchange, root.resolve("publish")));
        outer.start();
        Thread controller = new Thread(() -> {
            try {
                CpgameSharedHttpServerProvider.mounting(mount, () -> ServerMain.main(new String[]{
                    "--config", config.toString(), "--port", Integer.toString(port)}));
            } catch (Throwable error) { mount.failure = error; mount.ready.countDown(); }
        }, "gid42-managed-controller-test");
        controller.start();
        mount.ready.await();
        if (mount.failure != null) throw new IllegalStateException("Controller mount failed", mount.failure);
        if (secondMount != null) {
            Thread secondController = new Thread(() -> {
                try {
                    CpgameSharedHttpServerProvider.mounting(secondMount,
                        () -> invokeMain(secondJar, "com.cpgame.luckywheel.api.ServerMain",
                            new String[]{secondConfig.toString()}));
                } catch (Throwable error) { secondMount.failure = error; secondMount.ready.countDown(); }
            }, "second-shared-controller-test");
            secondController.start();
            secondMount.ready.await();
            if (secondMount.failure != null) throw new IllegalStateException("Second controller mount failed", secondMount.failure);
        }
        System.out.println("MANAGED_HOST_READY port=" + port + " pid=" + ProcessHandle.current().pid());
        new CountDownLatch(1).await();
    }

    private static void invokeMain(Path jar, String mainClass, String[] args) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
            SharedHostMachineTestMain.class.getClassLoader())) {
            Class<?> type = Class.forName(mainClass, true, loader);
            type.getMethod("main", String[].class).invoke(null, (Object) args);
        }
    }

    private static void serveStatic(HttpExchange exchange, Path publishRoot) throws IOException {
        String raw = exchange.getRequestURI().getPath();
        String prefix = "/play/";
        String remainder = raw.substring(prefix.length());
        int slash = remainder.indexOf('/');
        if (slash < 0) { exchange.sendResponseHeaders(404, -1); return; }
        String directory = remainder.substring(0, slash);
        String relative = remainder.substring(slash + 1);
        Path base = publishRoot.resolve(directory).normalize();
        Path file = base.resolve(relative.isBlank() ? "index.html" : relative).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); return; }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".html")) return "text/html; charset=UTF-8";
        if (name.endsWith(".js")) return "text/javascript; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static final class Mount implements CpgameSharedHttpServerProvider.MountTarget {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile CpgameSharedHttpServerProvider.VirtualHttpServer virtual;
        volatile Throwable failure;
        @Override public void mounted(CpgameSharedHttpServerProvider.VirtualHttpServer server) { virtual = server; ready.countDown(); }
        @Override public void unmounted(CpgameSharedHttpServerProvider.VirtualHttpServer server) { if (virtual == server) virtual = null; }
        void dispatch(HttpExchange exchange) throws IOException {
            if (virtual == null) { exchange.sendResponseHeaders(503, -1); return; }
            virtual.dispatch(exchange);
        }
    }
}
