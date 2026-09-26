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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * YAML 配置文件解析器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class YamlConfigFileParser implements ConfigFileParser {

    @Override
    /**
     * 支持
    */
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    @Override
    /**
     * 解析
    */
    public DataSyncConfigDefinition parse(Path file) throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream is = Files.newInputStream(file)) {
            Map<String, Object> map = yaml.load(is);
            return mapToConfig(map);
        }
    }

    /**
     * 映射转为配置。
     *
     * @param map 映射，不允许为 null
     * @return 数据Sync配置Definition 对象
     */
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

    /**
     * 获取字符串
     *
     * @param map 映射
     * @param key 键
     * @return 获取字符串的结果
     */
    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * 获取Int
     *
     * @param map 映射
     * @param key 键
     * @param defaultValue 默认值
     * @return 获取int的结果
     */
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

        /**
         * 规范构造器：三个字段名做 空 值 校验。
         *
         * <p>value class 前置条件——值类要求 空 值 敌对。三个组件在本类内唯一构造点
         * {@code mapToConfig} 中都取自 {@code m.getOrDefault(...)}，而 {@code m} 的值由
         * {@code String.valueOf(e.getValue())} 归一（空值会变成字符串 {@code "null"}），
         * 因此恒非 空。</p>
         *
         * @param sourceField 源字段
         * @param targetField 目标字段
         * @param converter 转换器
         */
        public DefaultDataSyncFieldMapping {
            sourceField = Objects.requireNonNull(sourceField, "sourceField 不能为 null");
            targetField = Objects.requireNonNull(targetField, "targetField 不能为 null");
            converter = Objects.requireNonNull(converter, "converter 不能为 null");
        }

        @Override
        /**
         * 源字段
        */
        public String sourceField() {
            return sourceField;
        }

        @Override
        /**
         * Target字段
        */
        public String targetField() {
            return targetField;
        }

        @Override
        /**
         * 转换器
        */
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

        /**
         * 规范构造器：字段映射列表与参数映射做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。两个集合在本类唯一构造点
         * {@code mapToConfig} 中由 {@code new ArrayList<>()} / {@code new LinkedHashMap<>()}
         * 现地构造，恒非 空 且元素非 空。</p>
         *
         * <p>{@code params} 采用 {@link LinkedHashMap} 快照 + {@link Collections#unmodifiableMap}
         * 而非 {@code Map.copyOf}：YAML 中 {@code key:}（无值）会解析出 空 值，
         * {@code Map.copyOf} 会因此抛 空指针。</p>
         *
         * @param mappings 字段映射列表
         * @param params 参数映射
         * @param directoryPath 目录路径
         */
        public DefaultDirectoryConfigDefinition {
            mappings = List.copyOf(Objects.requireNonNull(mappings, "mappings 不能为 null"));
            params = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(params, "params 不能为 null")));
            directoryPath = Objects.requireNonNull(directoryPath, "directoryPath 不能为 null");
        }

        @Override
        /**
         * 源id
        */
        public String sourceId() {
            return sourceId;
        }

        @Override
        /**
         * 输出id
        */
        public String outputId() {
            return outputId;
        }

        @Override
        /**
         * sinkid
        */
        public String sinkId() {
            return sinkId;
        }

        @Override
        /**
         * Mappings
        */
        public List<DataSyncFieldMapping> mappings() {
            return mappings;
        }

        @Override
        /**
         * 批量
        */
        public int batch() {
            return batch;
        }

        @Override
        /**
         * cron类型
        */
        public String cronType() {
            return cronType;
        }

        @Override
        /**
         * Cron
        */
        public String cron() {
            return cron;
        }

        @Override
        /**
         * 参数
        */
        public Map<String, Object> params() {
            return params;
        }

        @Override
        /**
         * 目录路径
        */
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

        /**
         * 规范构造器：字段映射列表与参数映射做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。两个集合在本类唯一构造点
         * {@code mapToConfig} 中由 {@code new ArrayList<>()} / {@code new LinkedHashMap<>()}
         * 现地构造，恒非 空 且元素非 空。</p>
         *
         * <p>{@code params} 采用 {@link LinkedHashMap} 快照 + {@link Collections#unmodifiableMap}
         * 而非 {@code Map.copyOf}：YAML 中 {@code key:}（无值）会解析出 空 值，
         * {@code Map.copyOf} 会因此抛 空指针。</p>
         *
         * @param mappings 字段映射列表
         * @param params 参数映射
         * @param filePath 文件路径
         */
        public DefaultFileConfigDefinition {
            mappings = List.copyOf(Objects.requireNonNull(mappings, "mappings 不能为 null"));
            params = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(params, "params 不能为 null")));
            filePath = Objects.requireNonNull(filePath, "filePath 不能为 null");
        }

        @Override
        /**
         * 源id
        */
        public String sourceId() {
            return sourceId;
        }

        @Override
        /**
         * 输出id
        */
        public String outputId() {
            return outputId;
        }

        @Override
        /**
         * sinkid
        */
        public String sinkId() {
            return sinkId;
        }

        @Override
        /**
         * Mappings
        */
        public List<DataSyncFieldMapping> mappings() {
            return mappings;
        }

        @Override
        /**
         * 批量
        */
        public int batch() {
            return batch;
        }

        @Override
        /**
         * cron类型
        */
        public String cronType() {
            return cronType;
        }

        @Override
        /**
         * Cron
        */
        public String cron() {
            return cron;
        }

        @Override
        /**
         * 参数
        */
        public Map<String, Object> params() {
            return params;
        }

        @Override
        /**
         * 文件路径
        */
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

        /**
         * 规范构造器：字段映射列表与参数映射做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。两个集合在本类唯一构造点
         * {@code mapToConfig} 中由 {@code new ArrayList<>()} / {@code new LinkedHashMap<>()}
         * 现地构造，恒非 空 且元素非 空。</p>
         *
         * <p>{@code params} 采用 {@link LinkedHashMap} 快照 + {@link Collections#unmodifiableMap}
         * 而非 {@code Map.copyOf}：YAML 中 {@code key:}（无值）会解析出 空 值，
         * {@code Map.copyOf} 会因此抛 空指针。</p>
         *
         * @param mappings 字段映射列表
         * @param params 参数映射
         * @param text 文本内容
         */
        public DefaultTextConfigDefinition {
            mappings = List.copyOf(Objects.requireNonNull(mappings, "mappings 不能为 null"));
            params = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(params, "params 不能为 null")));
            text = Objects.requireNonNull(text, "text 不能为 null");
        }

        @Override
        /**
         * 源id
        */
        public String sourceId() {
            return sourceId;
        }

        @Override
        /**
         * 输出id
        */
        public String outputId() {
            return outputId;
        }

        @Override
        /**
         * sinkid
        */
        public String sinkId() {
            return sinkId;
        }

        @Override
        /**
         * Mappings
        */
        public List<DataSyncFieldMapping> mappings() {
            return mappings;
        }

        @Override
        /**
         * 批量
        */
        public int batch() {
            return batch;
        }

        @Override
        /**
         * cron类型
        */
        public String cronType() {
            return cronType;
        }

        @Override
        /**
         * Cron
        */
        public String cron() {
            return cron;
        }

        @Override
        /**
         * 参数
        */
        public Map<String, Object> params() {
            return params;
        }

        @Override
        /**
         * 文本
        */
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

        /**
         * 规范构造器：字段映射列表与参数映射做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。两个集合在本类唯一构造点
         * {@code mapToConfig} 中由 {@code new ArrayList<>()} / {@code new LinkedHashMap<>()}
         * 现地构造，恒非 空 且元素非 空。</p>
         *
         * <p>{@code params} 采用 {@link LinkedHashMap} 快照 + {@link Collections#unmodifiableMap}
         * 而非 {@code Map.copyOf}：YAML 中 {@code key:}（无值）会解析出 空 值，
         * {@code Map.copyOf} 会因此抛 空指针。</p>
         *
         * @param mappings 字段映射列表
         * @param params 参数映射
         */
        public SimpleConfigDefinition {
            mappings = List.copyOf(Objects.requireNonNull(mappings, "mappings 不能为 null"));
            params = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(params, "params 不能为 null")));
        }

        @Override
        /**
         * 源id
        */
        public String sourceId() {
            return sourceId;
        }

        @Override
        /**
         * 输出id
        */
        public String outputId() {
            return outputId;
        }

        @Override
        /**
         * sinkid
        */
        public String sinkId() {
            return sinkId;
        }

        @Override
        /**
         * Mappings
        */
        public List<DataSyncFieldMapping> mappings() {
            return mappings;
        }

        @Override
        /**
         * 批量
        */
        public int batch() {
            return batch;
        }

        @Override
        /**
         * cron类型
        */
        public String cronType() {
            return cronType;
        }

        @Override
        /**
         * Cron
        */
        public String cron() {
            return cron;
        }

        @Override
        /**
         * 参数
        */
        public Map<String, Object> params() {
            return params;
        }
    }
}
