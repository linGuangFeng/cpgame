package com.cpgame.admin;

import java.nio.file.Path;
import java.util.Locale;

public final class AdminSettings {
    private final Path artifactRoot;
    private final Path runtimeRoot;
    private final String address;
    private final int port;

    public AdminSettings(Path artifactRoot, Path runtimeRoot, String address, int port) {
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
        this.address = address == null || address.isBlank() ? "0.0.0.0" : address.strip();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
        this.port = port;
    }

    public Path getRoot() { return artifactRoot; }
    public Path runtimeRoot() { return runtimeRoot; }
    public String getPublicAddress() { return address; }
    public int getPublicPort() { return port; }
    public boolean isPublicEnabled() { return true; }

    public static AdminSettings fromArgs(String[] args) {
        Path artifactRoot = envPath("CPGAME_ARTIFACT_ROOT", Path.of("D:/work/hd/cpgame"));
        Path runtimeRoot = envPath("CPGAME_ADMIN_RUNTIME", Path.of("D:/work/hd/cpgame/admin/var"));
        String address = firstNonBlank(System.getenv("CPGAME_ADMIN_ADDRESS"), "0.0.0.0");
        int port = envInt("CPGAME_ADMIN_PORT", 8000);
        if (args != null) {
            for (String arg : args) {
                if (arg == null) continue;
                int separator = arg.indexOf('=');
                String name = separator < 0 ? arg : arg.substring(0, separator);
                String value = separator < 0 ? "" : arg.substring(separator + 1);
                switch (name) {
                    case "--root" -> artifactRoot = Path.of(value);
                    case "--runtime" -> runtimeRoot = Path.of(value);
                    case "--address" -> address = value;
                    case "--port" -> port = Integer.parseInt(value);
                    default -> { }
                }
            }
        }
        return new AdminSettings(artifactRoot, runtimeRoot, address, port);
    }

    private static Path envPath(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        return Integer.parseInt(value.trim());
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    @Override
    public String toString() {
        return "admin root=" + artifactRoot + " runtime=" + runtimeRoot
            + " bind=" + address + ":" + port + " locale=" + Locale.getDefault();
    }
}
