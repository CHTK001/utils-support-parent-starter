package com.chua.starter.datasync.scanner;

import com.chua.common.support.utils.StringUtils;
import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.DirectoryConfigDefinition;
import com.chua.starter.datasync.config.FileConfigDefinition;
import com.chua.starter.datasync.config.TextConfigDefinition;
import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML 配置文件解析器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class YamlConfigFileParser implements ConfigFileParser {

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    @Override
    public DataSyncConfigDefinition parse(Path file) throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream is = Files.newInputStream(file)) {
            Map<String, Object> map = yaml.load(is);
            return mapToConfig(map);
        }
    }

    DataSyncConfigDefinition mapToConfig(Map<String, Object> map) {
        String inputId = getString(map, "inputId");
        String sourceId = getString(map, "sourceId");
        String outputId = getString(map, "outputId");
        String sinkId = getString(map, "sinkId");
        int batch = getInt(map, "batch", 100);
        String cronType = getString(map, "cronType");
        String cron = getString(map, "cron");
        String directoryPath = getString(map, "directoryPath");
        String filePath = getString(map, "filePath");
        String text = getString(map, "text");

        List<Map<String, String>> mappings = new ArrayList<>();
        Object mappingsObj = map.get("mappings");
        if (mappingsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        entry.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                    mappings.add(entry);
                }
            }
        }

        List<DataSyncFieldMapping> fieldMappings = new ArrayList<>();
        for (Map<String, String> m : mappings) {
            fieldMappings.add(new DefaultDataSyncFieldMapping(
                    m.getOrDefault("sourceField", ""),
                    m.getOrDefault("targetField", ""),
                    m.getOrDefault("converter", "toString")
            ));
        }

        Map<String, Object> params = new LinkedHashMap<>();
        Object paramsObj = map.get("params");
        if (paramsObj instanceof Map<?, ?> p) {
            for (Map.Entry<?, ?> e : p.entrySet()) {
                params.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        if (StringUtils.isNotEmpty(directoryPath)) {
            return new DefaultDirectoryConfigDefinition(
                    getString(map, "mappingId"),
                    inputId, sourceId, outputId, sinkId,
                    fieldMappings, batch, cronType, cron, params, directoryPath
            );
        }

        if (StringUtils.isNotEmpty(filePath)) {
            return new DefaultFileConfigDefinition(
                    getString(map, "mappingId"),
                    inputId, sourceId, outputId, sinkId,
                    fieldMappings, batch, cronType, cron, params, filePath
            );
        }

        if (text != null) {
            return new DefaultTextConfigDefinition(
                    getString(map, "mappingId"),
                    inputId, sourceId, outputId, sinkId,
                    fieldMappings, batch, cronType, cron, params, text
            );
        }

        return new SimpleConfigDefinition(
                inputId, sourceId, outputId, sinkId,
                fieldMappings, batch, cronType, cron, params
        );
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    /**
     * 默认字段映射。
     */
    private record DefaultDataSyncFieldMapping(String sourceField, String targetField, String converter)
            implements DataSyncFieldMapping {
        @Override
        public String sourceField() {
            return sourceField;
        }

        @Override
        public String targetField() {
            return targetField;
        }

        @Override
        public String converter() {
            return converter;
        }
    }

    /**
     * 默认目录配置定义。
     */
    private record DefaultDirectoryConfigDefinition(
            String mappingId,
            String inputId,
            String sourceId,
            String outputId,
            String sinkId,
            List<DataSyncFieldMapping> mappings,
            int batch,
            String cronType,
            String cron,
            Map<String, Object> params,
            String directoryPath
    ) implements DirectoryConfigDefinition {
        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public String outputId() {
            return outputId;
        }

        @Override
        public String sinkId() {
            return sinkId;
        }

        @Override
        public List<DataSyncFieldMapping> mappings() {
            return mappings;
        }

        @Override
        public int batch() {
            return batch;
        }

        @Override
        public String cronType() {
            return cronType;
        }

        @Override
        public String cron() {
            return cron;
        }

        @Override
        public Map<String, Object> params() {
            return params;
        }

        @Override
        public String directoryPath() {
            return directoryPath;
        }
    }

    /**
     * 默认文件配置定义。
     */
    private record DefaultFileConfigDefinition(
            String mappingId,
            String inputId,
            String sourceId,
            String outputId,
            String sinkId,
            List<DataSyncFieldMapping> mappings,
            int batch,
            String cronType,
            String cron,
            Map<String, Object> params,
            String filePath
    ) implements FileConfigDefinition {
        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public String outputId() {
            return outputId;
        }

        @Override
        public String sinkId() {
            return sinkId;
        }

        @Override
        public List<DataSyncFieldMapping> mappings() {
            return mappings;
        }

        @Override
        public int batch() {
            return batch;
        }

        @Override
        public String cronType() {
            return cronType;
        }

        @Override
        public String cron() {
            return cron;
        }

        @Override
        public Map<String, Object> params() {
            return params;
        }

        @Override
        public String filePath() {
            return filePath;
        }
    }

    /**
     * 默认文本配置定义。
     */
    private record DefaultTextConfigDefinition(
            String mappingId,
            String inputId,
            String sourceId,
            String outputId,
            String sinkId,
            List<DataSyncFieldMapping> mappings,
            int batch,
            String cronType,
            String cron,
            Map<String, Object> params,
            String text
    ) implements TextConfigDefinition {
        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public String outputId() {
            return outputId;
        }

        @Override
        public String sinkId() {
            return sinkId;
        }

        @Override
        public List<DataSyncFieldMapping> mappings() {
            return mappings;
        }

        @Override
        public int batch() {
            return batch;
        }

        @Override
        public String cronType() {
            return cronType;
        }

        @Override
        public String cron() {
            return cron;
        }

        @Override
        public Map<String, Object> params() {
            return params;
        }

        @Override
        public String text() {
            return text;
        }
    }

    /**
     * 简单配置定义。
     */
    private record SimpleConfigDefinition(
            String inputId,
            String sourceId,
            String outputId,
            String sinkId,
            List<DataSyncFieldMapping> mappings,
            int batch,
            String cronType,
            String cron,
            Map<String, Object> params
    ) implements DataSyncConfigDefinition {
        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public String outputId() {
            return outputId;
        }

        @Override
        public String sinkId() {
            return sinkId;
        }

        @Override
        public List<DataSyncFieldMapping> mappings() {
            return mappings;
        }

        @Override
        public int batch() {
            return batch;
        }

        @Override
        public String cronType() {
            return cronType;
        }

        @Override
        public String cron() {
            return cron;
        }

        @Override
        public Map<String, Object> params() {
            return params;
        }
    }
}
