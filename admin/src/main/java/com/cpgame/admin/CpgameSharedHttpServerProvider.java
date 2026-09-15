package com.cpgame.admin;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.spi.HttpServerProvider;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Captures legacy JDK {@link HttpServer} controllers and mounts their handlers into the one
 * CPGame HTTP listener. The shared host itself is delegated to the JDK provider; only controller
 * creation performed inside {@link #mounting(MountTarget, CheckedRunnable)} is virtualized.
 */
public final class CpgameSharedHttpServerProvider extends HttpServerProvider {
    public static final String PROVIDER_PROPERTY = "com.sun.net.httpserver.HttpServerProvider";
    private static final ThreadLocal<MountTarget> CURRENT_MOUNT = new ThreadLocal<>();

    private final HttpServerProvider delegate;

    public CpgameSharedHttpServerProvider() {
        this(defaultProvider());
    }

    CpgameSharedHttpServerProvider(HttpServerProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public HttpServer createHttpServer(InetSocketAddress address, int backlog) throws IOException {
        MountTarget target = CURRENT_MOUNT.get();
        return target == null ? delegate.createHttpServer(address, backlog)
            : new VirtualHttpServer(address, target);
    }

    @Override
    public HttpsServer createHttpsServer(InetSocketAddress address, int backlog) throws IOException {
        if (CURRENT_MOUNT.get() != null) {
            throw new IOException("共享试玩模块不支持自行创建 HTTPS 监听器");
        }
        return delegate.createHttpsServer(address, backlog);
    }

    public static void mounting(MountTarget target, CheckedRunnable action) throws Exception {
        Objects.requireNonNull(target, "target");
        if (CURRENT_MOUNT.get() != null) throw new IllegalStateException("试玩 Controller 挂载上下文不可嵌套");
        CURRENT_MOUNT.set(target);
        try {
            action.run();
        } finally {
            CURRENT_MOUNT.remove();
        }
    }

    private static HttpServerProvider defaultProvider() {
        try {
            Class<?> type = Class.forName("sun.net.httpserver.DefaultHttpServerProvider");
            return (HttpServerProvider) type.getConstructor().newInstance();
        } catch (InvocationTargetException error) {
            Throwable cause = error.getTargetException();
            throw new IllegalStateException("无法初始化 JDK HTTP Server", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                "无法访问 JDK HTTP Server；共享试玩 JVM 必须导出 jdk.httpserver/sun.net.httpserver", error);
        }
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    public interface MountTarget {
        void mounted(VirtualHttpServer server);
        void unmounted(VirtualHttpServer server);
    }

    /** A controller-local server whose contexts are dispatched by the shared host. */
    public static final class VirtualHttpServer extends HttpServer {
        private final InetSocketAddress address;
        private final MountTarget target;
        private final Map<String, VirtualHttpContext> contexts = new LinkedHashMap<>();
        private volatile Executor executor;
        private volatile boolean started;

        private VirtualHttpServer(InetSocketAddress address, MountTarget target) {
            this.address = address;
            this.target = target;
        }

        @Override public void bind(InetSocketAddress address, int backlog) {
            throw new IllegalStateException("虚拟试玩 Server 已由共享端口托管，不能再次 bind");
        }

        @Override public synchronized void start() {
            if (started) return;
            started = true;
            target.mounted(this);
        }

        @Override public synchronized void stop(int delay) {
            if (!started) return;
            started = false;
            target.unmounted(this);
        }

        @Override public void setExecutor(Executor executor) { this.executor = executor; }
        @Override public Executor getExecutor() { return executor; }
        @Override public InetSocketAddress getAddress() { return address; }

        @Override public synchronized HttpContext createContext(String path, HttpHandler handler) {
            if (handler == null) throw new NullPointerException("handler");
            VirtualHttpContext context = (VirtualHttpContext) createContext(path);
            context.setHandler(handler);
            return context;
        }

        @Override public synchronized HttpContext createContext(String path) {
            validatePath(path);
            if (contexts.containsKey(path)) throw new IllegalArgumentException("context already exists: " + path);
            VirtualHttpContext context = new VirtualHttpContext(this, path);
            contexts.put(path, context);
            return context;
        }

        @Override public synchronized void removeContext(String path) {
            if (contexts.remove(path) == null) throw new IllegalArgumentException("context does not exist: " + path);
        }

        @Override public synchronized void removeContext(HttpContext context) {
            if (context == null || contexts.get(context.getPath()) != context) {
                throw new IllegalArgumentException("context does not belong to this server");
            }
            contexts.remove(context.getPath());
        }

        public void dispatch(HttpExchange exchange) throws IOException {
            VirtualHttpContext context;
            synchronized (this) {
                String requestPath = exchange.getRequestURI().getPath();
                context = contexts.values().stream()
                    .filter(candidate -> matches(candidate.path, requestPath))
                    .max(java.util.Comparator.comparingInt(candidate -> candidate.path.length()))
                    .orElse(null);
            }
            if (!started || context == null || context.handler == null) {
                byte[] body = "当前游戏没有匹配的 Controller 路由".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(404, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                return;
            }
            Authenticator authenticator = context.authenticator;
            if (authenticator != null) {
                Authenticator.Result result = authenticator.authenticate(exchange);
                if (!(result instanceof Authenticator.Success)) return;
                exchange.setAttribute("principal", ((Authenticator.Success) result).getPrincipal());
            }
            new Filter.Chain(List.copyOf(context.filters), context.handler).doFilter(exchange);
        }

        private static boolean matches(String context, String request) {
            if (!request.startsWith(context)) return false;
            return context.endsWith("/") || request.length() == context.length()
                || request.charAt(context.length()) == '/';
        }

        private static void validatePath(String path) {
            if (path == null) throw new NullPointerException("path");
            if (!path.startsWith("/")) throw new IllegalArgumentException("context path must start with /");
        }
    }

    private static final class VirtualHttpContext extends HttpContext {
        private final VirtualHttpServer server;
        private final String path;
        private final Map<String, Object> attributes = Collections.synchronizedMap(new LinkedHashMap<>());
        private final List<Filter> filters = Collections.synchronizedList(new ArrayList<>());
        private volatile HttpHandler handler;
        private volatile Authenticator authenticator;

        private VirtualHttpContext(VirtualHttpServer server, String path) {
            this.server = server;
            this.path = path;
        }

        @Override public HttpHandler getHandler() { return handler; }
        @Override public void setHandler(HttpHandler handler) { this.handler = Objects.requireNonNull(handler); }
        @Override public String getPath() { return path; }
        @Override public HttpServer getServer() { return server; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public List<Filter> getFilters() { return filters; }
        @Override public Authenticator setAuthenticator(Authenticator authenticator) {
            Authenticator previous = this.authenticator;
            this.authenticator = authenticator;
            return previous;
        }
        @Override public Authenticator getAuthenticator() { return authenticator; }
    }

    /** Kept explicit so accidental HTTPS mounting fails instead of opening another port. */
    @SuppressWarnings("unused")
    private abstract static class UnsupportedVirtualHttpsServer extends HttpsServer {
        @Override public void setHttpsConfigurator(HttpsConfigurator config) { throw new UnsupportedOperationException(); }
    }
}
