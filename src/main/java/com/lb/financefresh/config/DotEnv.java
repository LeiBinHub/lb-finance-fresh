package com.lb.financefresh.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Secrets stay in the environment or a local .env file. System environment wins. */
public final class DotEnv {

    private static final Map<String, String> FILE = read();

    private DotEnv() {}

    public static String get(String name) {
        String env = System.getenv(name);
        if (env != null && !env.isBlank()) return env.trim();
        String prop = System.getProperty(name);
        if (prop != null && !prop.isBlank()) return prop.trim();
        return FILE.getOrDefault(name, "").trim();
    }

    public static boolean has(String name) {
        return !get(name).isEmpty();
    }

    public static void export(String... names) {
        for (String name : names) {
            if (System.getenv(name) != null && !System.getenv(name).isBlank()) continue;
            if (System.getProperty(name) != null && !System.getProperty(name).isBlank()) continue;
            String value = FILE.get(name);
            if (value != null && !value.isBlank()) System.setProperty(name, value.trim());
        }
    }

    private static Map<String, String> read() {
        Path path = Path.of(".env");
        if (!Files.exists(path)) return Map.of();
        Map<String, String> out = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) continue;
                int eq = text.indexOf('=');
                if (eq <= 0) continue;
                String key = text.substring(0, eq).strip();
                String value = text.substring(eq + 1).strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(key, value);
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return Map.copyOf(out);
    }
}
