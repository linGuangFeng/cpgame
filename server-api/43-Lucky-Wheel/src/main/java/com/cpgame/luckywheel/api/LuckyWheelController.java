package com.cpgame.luckywheel.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

public final class LuckyWheelController {
    @FunctionalInterface private interface Action { String run(Map<String,String> form, HttpExchange exchange) throws Exception; }
    private final Supplier<LuckyWheelService> serviceSupplier;
    private final Supplier<Map<String,Object>> healthSupplier;
    public LuckyWheelController(Supplier<LuckyWheelService> serviceSupplier,
                                Supplier<Map<String,Object>> healthSupplier) {
        this.serviceSupplier = serviceSupplier;
        this.healthSupplier = healthSupplier;
    }

    public void register(HttpServer server) {
        post(server, "/cp/api/v1/auth/verify", (form, exchange) -> service().authenticate(form));
        post(server, "/cp/api/v1/auth/session", (form, exchange) -> service().authenticate(form));
        post(server, "/cp/api/v1/lucky-wheel/init", (form, exchange) -> service().authenticate(form));
        post(server, "/cp/api/v1/lucky-wheel/config", (form, exchange) -> service().config(form));
        post(server, "/cp/api/v1/lucky-wheel/spin", (form, exchange) ->
                service().spin(form, exchange.getRequestHeaders().getFirst("Idempotency-Key")));
        post(server, "/cp/api/v1/lucky-wheel/log-list", (form, exchange) -> service().historyList(form));
        post(server, "/cp/api/v1/lucky-wheel/log-view", (form, exchange) -> service().historyView(form));
        post(server, "/cp/api/v1/lucky-wheel/balance", (form, exchange) -> service().balance(form));
        post(server, "/cp/api/v1/ping", (form, exchange) -> service().ping(form));
        server.createContext("/api/report/timing", exchange -> { addCors(exchange); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.createContext("/health", exchange -> send(exchange, 200, JsonCodec.write(healthSupplier.get())));
    }

    private LuckyWheelService service() {
        LuckyWheelService service = serviceSupplier.get();
        if (service == null) throw new IllegalStateException("Controller 当前已卸载，需由共享 JVM 重挂");
        return service;
    }

    private void post(HttpServer server, String path, Action action) {
        server.createContext(path, exchange -> {
            addCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { send(exchange, 405, ProtocolCodec.error(405, "Method Not Allowed")); return; }
            try {
                send(exchange, 200, action.run(ProtocolCodec.readForm(exchange), exchange));
            } catch (SecurityException e) {
                send(exchange, 200, ProtocolCodec.error(401, e.getMessage()));
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                send(exchange, 200, ProtocolCodec.error(422, e.getMessage()));
            } catch (IllegalStateException e) {
                send(exchange, 503, ProtocolCodec.error(503, e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, ProtocolCodec.error(500, "Internal Server Error"));
            }
        });
    }
    static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        addCors(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Idempotency-Key");
    }
}
