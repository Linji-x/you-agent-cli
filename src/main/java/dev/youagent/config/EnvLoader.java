package dev.youagent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class EnvLoader {
    private EnvLoader() {
    }

    static Map<String, String> load(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = stripQuotes(line.substring(separator + 1).trim());
            values.putIfAbsent(key, value);
        }
        return values;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
