package com.cpgame.admin;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads a Spring Boot Controller JAR as a non-web application context and dispatches its annotated
 * controller methods from the shared JDK HTTP listener. No embedded Tomcat and no game port are
 * created.
 */
final class CpgameSpringControllerMount implements AutoCloseable {
    private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final String REST_ADVICE = "org.springframework.web.bind.annotation.RestControllerAdvice";
    private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
    private static final String POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
    private static final String REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam";
    private static final String REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader";
    private static final String EXCEPTION_HANDLER = "org.springframework.web.bind.annotation.ExceptionHandler";
    private static final String DEFAULT_NONE = "\n\n\n";

    private final String directory;
    private final Object context;
    private final Object mapper;
    private final Method writeValueAsBytes;
    private final List<Route> routes;
    private final List<Advice> advice;

    private CpgameSpringControllerMount(String directory, Object context, Object mapper,
                                        List<Route> routes, List<Advice> advice) throws Exception {
        this.directory = directory;
        this.context = context;
        this.mapper = mapper;
        this.writeValueAsBytes = mapper.getClass().getMethod("writeValueAsBytes", Object.class);
        this.routes = routes;
        this.advice = advice;
    }

    static CpgameSpringControllerMount start(String directory, ClassLoader loader, Class<?> application,
                                             List<String> properties) throws Exception {
        Class<?> springApplication = Class.forName("org.springframework.boot.SpringApplication", true, loader);
        List<String> arguments = new ArrayList<>(properties);
        arguments.add("--spring.main.web-application-type=none");
        arguments.add("--spring.main.register-shutdown-hook=false");
        Object context;
        try {
            context = springApplication.getMethod("run", Class.class, String[].class)
                .invoke(null, application, arguments.toArray(String[]::new));
        } catch (InvocationTargetException error) {
            throw rethrow(error);
        }

        try {
            Object mapper = bean(context, Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, loader));
            List<Route> routes = routes(context, loader);
            if (routes.isEmpty()) throw new IllegalArgumentException("Spring Controller 没有可挂载的 HTTP 路由");
            return new CpgameSpringControllerMount(directory, context, mapper, routes, advice(context, loader));
        } catch (Exception error) {
            closeContext(context);
            throw error;
        }
    }

    boolean handles(String method, String path) {
        return route(method, path) != null;
    }

    void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        Route route = route(method, path);
        if (route == null) {
            send(exchange, 404, "application/json; charset=UTF-8",
                bytes(Map.of("ok", false, "message", "当前游戏没有匹配的 Controller 路由")));
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> parameters = parameters(exchange.getRequestURI().getRawQuery(), body,
            exchange.getRequestHeaders().getFirst("Content-Type"));
        try {
            Object[] arguments = bind(route.method, exchange, parameters);
            Object result = route.method.invoke(route.bean, arguments);
            writeResult(exchange, result);
        } catch (InvocationTargetException error) {
            writeFailure(exchange, error.getTargetException());
        } catch (Throwable error) {
            writeFailure(exchange, error);
        }
    }

    @Override public void close() {
        closeContext(context);
    }

    private Route route(String method, String path) {
        return routes.stream().filter(candidate -> candidate.httpMethod.equals(method)
            && candidate.path.equals(path)).findFirst().orElse(null);
    }

    private Object[] bind(Method method, HttpExchange exchange, Map<String, String> form) throws Exception {
        Parameter[] parameters = method.getParameters();
        Object[] result = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            Annotation requestParam = annotation(parameter.getAnnotations(), REQUEST_PARAM);
            Annotation requestHeader = annotation(parameter.getAnnotations(), REQUEST_HEADER);
            if (requestParam != null) {
                if (Map.class.isAssignableFrom(parameter.getType())) {
                    result[index] = new LinkedHashMap<>(form);
                } else {
                    String name = annotationString(requestParam, "value", annotationString(requestParam, "name", ""));
                    if (name.isBlank()) name = parameter.getName();
                    String value = form.get(name);
                    String fallback = annotationString(requestParam, "defaultValue", DEFAULT_NONE);
                    if (value == null && !isDefaultNone(fallback)) value = fallback;
                    boolean required = annotationBoolean(requestParam, "required", true);
                    if (value == null && required) throw new IllegalArgumentException(name + " is required");
                    result[index] = convert(value, parameter.getType());
                }
            } else if (requestHeader != null) {
                String name = annotationString(requestHeader, "value", annotationString(requestHeader, "name", ""));
                String value = exchange.getRequestHeaders().getFirst(name);
                String fallback = annotationString(requestHeader, "defaultValue", DEFAULT_NONE);
                if (value == null && !isDefaultNone(fallback)) value = fallback;
                boolean required = annotationBoolean(requestHeader, "required", true);
                if (value == null && required) throw new IllegalArgumentException(name + " header is required");
                result[index] = convert(value, parameter.getType());
            } else if (parameter.getType().getName().endsWith(".HttpServletRequest")) {
                result[index] = servletRequest(parameter.getType(), exchange, form);
            } else {
                throw new IllegalArgumentException("不支持的 Spring Controller 参数：" + parameter);
            }
        }
        return result;
    }

    private Object servletRequest(Class<?> type, HttpExchange exchange, Map<String, String> form) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getHeader" -> exchange.getRequestHeaders().getFirst(String.valueOf(args[0]));
            case "getHeaderNames" -> java.util.Collections.enumeration(exchange.getRequestHeaders().keySet());
            case "getHeaders" -> java.util.Collections.enumeration(
                exchange.getRequestHeaders().getOrDefault(String.valueOf(args[0]), List.of()));
            case "getServerName" -> exchange.getLocalAddress().getHostString();
            case "getServerPort" -> exchange.getLocalAddress().getPort();
            case "getScheme" -> "http";
            case "isSecure" -> false;
            case "getMethod" -> exchange.getRequestMethod();
            case "getRequestURI" -> exchange.getRequestURI().getPath();
            case "getRequestURL" -> new StringBuffer("http://")
                .append(exchange.getRequestHeaders().getFirst("Host")).append(exchange.getRequestURI().getPath());
            case "getQueryString" -> exchange.getRequestURI().getRawQuery();
            case "getParameter" -> form.get(String.valueOf(args[0]));
            case "getParameterNames" -> java.util.Collections.enumeration(form.keySet());
            case "getParameterMap" -> form.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, entry -> new String[]{entry.getValue()}));
            case "getParameterValues" -> form.containsKey(String.valueOf(args[0]))
                ? new String[]{form.get(String.valueOf(args[0]))} : null;
            case "toString" -> "SharedCpgameServletRequest[" + directory + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private void writeResult(HttpExchange exchange, Object value) throws Exception {
        if (value != null && value.getClass().getName().equals("org.springframework.http.ResponseEntity")) {
            Object status = value.getClass().getMethod("getStatusCode").invoke(value);
            int code = (int) status.getClass().getMethod("value").invoke(status);
            Object headers = value.getClass().getMethod("getHeaders").invoke(value);
            Object single = headers.getClass().getMethod("toSingleValueMap").invoke(headers);
            if (single instanceof Map<?, ?> map) map.forEach((name, header) -> {
                String stringValue = String.valueOf(header);
                if ("location".equalsIgnoreCase(String.valueOf(name)) && stringValue.startsWith("/")) {
                    stringValue = "/play/" + java.net.URLEncoder.encode(directory, StandardCharsets.UTF_8)
                        .replace("+", "%20") + stringValue;
                }
                exchange.getResponseHeaders().set(String.valueOf(name), stringValue);
            });
            Object body = value.getClass().getMethod("getBody").invoke(value);
            byte[] bytes = body == null ? new byte[0] : bytes(body);
            send(exchange, code, exchange.getResponseHeaders().getFirst("Content-Type"), bytes);
            return;
        }
        send(exchange, 200, "application/json; charset=UTF-8", bytes(value));
    }

    private void writeFailure(HttpExchange exchange, Throwable error) throws IOException {
        Throwable cause = unwrap(error);
        for (Advice candidate : advice) {
            if (!candidate.handledType.isInstance(cause)) continue;
            try {
                Object result = candidate.method.invoke(candidate.bean, cause);
                send(exchange, 200, "application/json; charset=UTF-8", bytes(result));
                return;
            } catch (ReflectiveOperationException ignored) {
                break;
            }
        }
        send(exchange, 400, "application/json; charset=UTF-8",
            bytes(Map.of("ok", false, "message", concise(cause))));
    }

    private byte[] bytes(Object value) throws IOException {
        try {
            return (byte[]) writeValueAsBytes.invoke(mapper, value);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getTargetException();
            if (cause instanceof IOException io) throw io;
            throw new IOException("无法序列化游戏 Controller 响应", cause);
        } catch (ReflectiveOperationException error) {
            throw new IOException("无法序列化游戏 Controller 响应", error);
        }
    }

    private static List<Route> routes(Object context, ClassLoader loader) throws Exception {
        Map<?, ?> beans = beansWithAnnotation(context, Class.forName(REST_CONTROLLER, true, loader));
        List<Route> result = new ArrayList<>();
        for (Object bean : beans.values()) {
            Class<?> type = userType(bean.getClass());
            String prefix = mappingPaths(annotation(type.getAnnotations(), REQUEST_MAPPING)).stream()
                .findFirst().orElse("");
            for (Method method : type.getMethods()) {
                List<Mapping> mappings = methodMappings(method);
                for (Mapping mapping : mappings) {
                    for (String path : mapping.paths) {
                        String fullPath = normalizePath(prefix, path);
                        for (String httpMethod : mapping.httpMethods) {
                            result.add(new Route(httpMethod, fullPath, bean, method));
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Advice> advice(Object context, ClassLoader loader) throws Exception {
        Map<?, ?> beans = beansWithAnnotation(context, Class.forName(REST_ADVICE, true, loader));
        List<Advice> result = new ArrayList<>();
        for (Object bean : beans.values()) {
            for (Method method : userType(bean.getClass()).getMethods()) {
                Annotation handler = annotation(method.getAnnotations(), EXCEPTION_HANDLER);
                if (handler == null || method.getParameterCount() != 1) continue;
                Object declared = annotationValue(handler, "value");
                if (declared == null || Array.getLength(declared) == 0) declared = annotationValue(handler, "exception");
                if (declared != null && Array.getLength(declared) > 0) {
                    for (int index = 0; index < Array.getLength(declared); index++) {
                        result.add(new Advice((Class<?>) Array.get(declared, index), bean, method));
                    }
                } else {
                    result.add(new Advice(method.getParameterTypes()[0], bean, method));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Mapping> methodMappings(Method method) {
        List<Mapping> result = new ArrayList<>();
        for (Annotation annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (GET_MAPPING.equals(name)) result.add(new Mapping(mappingPaths(annotation), List.of("GET")));
            if (POST_MAPPING.equals(name)) result.add(new Mapping(mappingPaths(annotation), List.of("POST")));
            if (REQUEST_MAPPING.equals(name)) {
                Object methods = annotationValue(annotation, "method");
                List<String> verbs = new ArrayList<>();
                if (methods != null) for (int index = 0; index < Array.getLength(methods); index++) {
                    verbs.add(String.valueOf(Array.get(methods, index)));
                }
                if (verbs.isEmpty()) verbs.addAll(List.of("GET", "POST"));
                result.add(new Mapping(mappingPaths(annotation), verbs));
            }
        }
        return result;
    }

    private static List<String> mappingPaths(Annotation annotation) {
        if (annotation == null) return List.of("");
        Object paths = annotationValue(annotation, "path");
        if (paths == null || Array.getLength(paths) == 0) paths = annotationValue(annotation, "value");
        if (paths == null || Array.getLength(paths) == 0) return List.of("");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < Array.getLength(paths); index++) result.add(String.valueOf(Array.get(paths, index)));
        return result;
    }

    private static Map<?, ?> beansWithAnnotation(Object context, Class<?> annotation) throws Exception {
        return (Map<?, ?>) context.getClass().getMethod("getBeansWithAnnotation", Class.class)
            .invoke(context, annotation);
    }

    private static Object bean(Object context, Class<?> type) throws Exception {
        return context.getClass().getMethod("getBean", Class.class).invoke(context, type);
    }

    private static Class<?> userType(Class<?> type) {
        Class<?> superclass = type.getSuperclass();
        return type.getName().contains("$$") && superclass != null ? superclass : type;
    }

    private static Annotation annotation(Annotation[] annotations, String type) {
        return Arrays.stream(annotations).filter(value -> value.annotationType().getName().equals(type))
            .findFirst().orElse(null);
    }

    private static Object annotationValue(Annotation annotation, String name) {
        if (annotation == null) return null;
        try { return annotation.annotationType().getMethod(name).invoke(annotation); }
        catch (ReflectiveOperationException ignored) { return null; }
    }

    private static String annotationString(Annotation annotation, String name, String fallback) {
        Object value = annotationValue(annotation, name);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean annotationBoolean(Annotation annotation, String name, boolean fallback) {
        Object value = annotationValue(annotation, name);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static boolean isDefaultNone(String value) {
        return value == null || DEFAULT_NONE.equals(value) || value.indexOf('\ue000') >= 0;
    }

    private static Object convert(String value, Class<?> type) {
        if (value == null) return type.isPrimitive() ? defaultValue(type) : null;
        if (type == String.class) return value;
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == long.class || type == Long.class) return Long.parseLong(value);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == BigDecimal.class) return new BigDecimal(value);
        throw new IllegalArgumentException("不支持的请求参数类型：" + type.getName());
    }

    private static Map<String, String> parameters(String rawQuery, byte[] body, String contentType) {
        Map<String, String> result = new LinkedHashMap<>();
        decodeForm(rawQuery, result);
        if (contentType == null || contentType.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded")) {
            decodeForm(new String(body, StandardCharsets.UTF_8), result);
        }
        return result;
    }

    private static void decodeForm(String value, Map<String, String> target) {
        if (value == null || value.isBlank()) return;
        for (String field : value.split("&")) {
            int equals = field.indexOf('=');
            String name = equals < 0 ? field : field.substring(0, equals);
            String content = equals < 0 ? "" : field.substring(equals + 1);
            target.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                URLDecoder.decode(content, StandardCharsets.UTF_8));
        }
    }

    private static String normalizePath(String prefix, String path) {
        String value = (prefix == null ? "" : prefix) + (path == null ? "" : path);
        if (value.isBlank()) return "/";
        if (!value.startsWith("/")) value = "/" + value;
        return value.replaceAll("/{2,}", "/");
    }

    private static RuntimeException rethrow(InvocationTargetException error) {
        Throwable cause = unwrap(error);
        return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(concise(cause), cause);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while ((result instanceof InvocationTargetException || result instanceof java.lang.reflect.UndeclaredThrowableException)
            && result.getCause() != null) result = result.getCause();
        return result;
    }

    private static String concise(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null && !contentType.isBlank()) exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static void closeContext(Object context) {
        try { context.getClass().getMethod("close").invoke(context); }
        catch (ReflectiveOperationException ignored) { }
    }

    private record Mapping(List<String> paths, List<String> httpMethods) { }
    private record Route(String httpMethod, String path, Object bean, Method method) { }
    private record Advice(Class<?> handledType, Object bean, Method method) { }
}
