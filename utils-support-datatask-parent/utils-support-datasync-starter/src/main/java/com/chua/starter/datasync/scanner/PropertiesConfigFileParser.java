package com.chua.starter.datasync.scanner;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Properties 配置文件解析器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PropertiesConfigFileParser implements ConfigFileParser {

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".properties");
    }

    @Override
    public DataSyncConfigDefinition parse(Path file) throws Exception {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(file)) {
            props.load(is);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        Map<String, Object> nested = buildNestedMap(map);
        return new YamlConfigFileParser().mapToConfig(nested);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildNestedMap(Map<String, Object> flat) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String[] parts = key.split("\\.");
            Map<String, Object> current = result;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                String next = (i + 1 < parts.length) ? parts[i + 1] : null;
                if (next == null) {
                    current.put(part, value);
                } else {
                    Map<String, Object> child = (Map<String, Object>) current.computeIfAbsent(part, k -> new LinkedHashMap<String, Object>());
                    current = child;
                }
            }
        }
        return result;
    }
}
