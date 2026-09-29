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
     *
     * <p>由 {@link YamlConfigFileParser#mapToConfig(Map)} 解析 YAML 中 {@code mappings}
     * 列表的每一项构造：三个值取自该条目的 {@code sourceField} / {@code targetField} /
     * {@code converter} 键，且先经 {@code String.valueOf(e.getValue())} 归一（YAML 空值会变成
     * 字符串 {@code "null"}），因此实际不可能传入 null。</p>
     *
     * @param sourceField 源字段名，从源记录中取值的键，如 {@code name}；YAML 缺项时为空串，不允许为 null
     * @param targetField 目标字段名，类型转换后写入目标记录的键，如 {@code user_name}；YAML 缺项时为空串，不允许为 null
     * @param converter 类型转换器标识，由 {@code FieldMappingConverter#convert} 接收并按名分派具体转换逻辑；
     * YAML 缺项时默认 {@code toString}，不允许为 null
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
     *
     * <p>由 {@link YamlConfigFileParser#mapToConfig(Map)} 在 YAML {@code directoryPath} 非空时
     * 构造，是本解析器内 {@link DirectoryConfigDefinition} 的唯一实现；其中
     * {@code mappingId} 不属于 {@link DataSyncConfigDefinition} 契约，是 YAML 层的额外挂载标识。</p>
     *
     * <p>可空性由唯一构造点 {@code mapToConfig} 决定：标识类组件一律经 {@code getString} 读取，
     * YAML 缺项时得到 null，且紧凑构造器未对它们做非空校验，故全部允许为 null；
     * {@code mappings} 与 {@code params} 在 {@code mapToConfig} 中由
     * {@code new ArrayList&lt;&gt;()} / {@code new LinkedHashMap&lt;&gt;()} 现地构造，恒非 null
     * （可能为空容器）；{@code directoryPath} 只有先通过 {@code StringUtils.isNotEmpty}
     * 判断才会走到本构造器，故恒非空。</p>
     *
     * @param mappingId 映射标识，取自 YAML 顶层 {@code mappingId}，源配置内唯一；调度器以它作为
     *                   trigger 缓存与上次执行时刻的键，缺项时为 null
     * @param inputId 输入标识（数据入口名），取自 YAML {@code inputId}；映射管理器以其反查关联映射，
     *                缺项时为 null
     * @param sourceId 输入源实例标识，取自 YAML {@code sourceId}；执行时据此从源目录解析出
     *                 {@code DataSyncAgentSource}，其方向必须为 {@code INPUT}，缺项时为 null
     * @param outputId 输出标识（响应式通道名），取自 YAML {@code outputId}；作为
     *                 {@code ReactorDataSyncExecutor} 的查找键，并与 {@code sinkId} 拼成订阅去重键，缺项时为 null
     * @param sinkId 输出 sink 实例标识，取自 YAML {@code sinkId}；其方向必须为 {@code OUTPUT}，缺项时为 null
     * @param mappings 字段映射列表，来源于 YAML {@code mappings} 列表；恒非 null，未配置时为空列表，
     *                 元素为非 null 的 {@link DataSyncFieldMapping} 实现
     * @param batch 每批提交给执行器的记录条数（单位：条），取自 YAML {@code batch}，兼容数字与数字字符串，
     *              缺项或解析失败时取 100；小于等于 0 时由调度器改用其默认批量值
     * @param cronType 定时类型标识，取自 YAML {@code cronType}（示例值 {@code fixed}）；当前调度器只依据
     *                 {@code cron} 判定触发时机，此值仅原样透传，允许为 null
     * @param cron Quartz 风格 cron 表达式，取自 YAML {@code cron}（示例 {@code "0/5 * * * * ?"}）；
     *                  为空或解析失败时降级为固定 1 分钟的 SimpleTrigger，允许为 null
     * @param params 透传给数据源的读取参数映射，取自 YAML {@code params}；键保持配置文件的书写顺序，
     *               值允许为 null（YAML 中 {@code key:} 无值即产生 null），整体恒非 null，缺项时为空映射
     * @param directoryPath 待扫描的源目录路径，取自 YAML {@code directoryPath}，恒非空，不允许为 null
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
     *
     * <p>由 {@link YamlConfigFileParser#mapToConfig(Map)} 在 {@code directoryPath} 为空且
     * {@code filePath} 非空时构造，是本解析器内 {@link FileConfigDefinition} 的唯一实现，
     * 用于「只监听单个文件」而非整目录的场景；其标识、批量、调度与参数组件的语义与
     * {@link DefaultDirectoryConfigDefinition} 完全一致，仅数据源位置由目录换成文件。</p>
     *
     * <p>可空性同样由唯一构造点 {@code mapToConfig} 决定：标识类组件经 {@code getString} 读取，
     * YAML 缺项时为 null 且紧凑构造器未校验；{@code mappings} 与 {@code params} 由
     * {@code mapToConfig} 现地新建容器，恒非 null；{@code filePath} 只有先通过
     * {@code StringUtils.isNotEmpty} 判断才会走到本构造器，故恒非空。</p>
     *
     * @param mappingId 映射标识，取自 YAML 顶层 {@code mappingId}，源配置内唯一；调度器以它作为
     *                   trigger 缓存与上次执行时刻的键，缺项时为 null
     * @param inputId 输入标识（数据入口名），取自 YAML {@code inputId}；映射管理器以其反查关联映射，
     *                缺项时为 null
     * @param sourceId 输入源实例标识，取自 YAML {@code sourceId}；执行时据此解析出
     *                 {@code DataSyncAgentSource}，其方向必须为 {@code INPUT}，缺项时为 null
     * @param outputId 输出标识（响应式通道名），取自 YAML {@code outputId}；作为
     *                 {@code ReactorDataSyncExecutor} 的查找键，并与 {@code sinkId} 拼成订阅去重键，缺项时为 null
     * @param sinkId 输出 sink 实例标识，取自 YAML {@code sinkId}；其方向必须为 {@code OUTPUT}，缺项时为 null
     * @param mappings 字段映射列表，来源于 YAML {@code mappings} 列表；恒非 null，未配置时为空列表，
     *                 元素为非 null 的 {@link DataSyncFieldMapping} 实现
     * @param batch 每批提交给执行器的记录条数（单位：条），取自 YAML {@code batch}，兼容数字与数字字符串，
     *              缺项或解析失败时取 100；小于等于 0 时由调度器改用其默认批量值
     * @param cronType 定时类型标识，取自 YAML {@code cronType}（示例值 {@code fixed}）；当前调度器只依据
     *                 {@code cron} 判定触发时机，此值仅原样透传，允许为 null
     * @param cron Quartz 风格 cron 表达式，取自 YAML {@code cron}（示例 {@code "0/5 * * * * ?"}）；
     *                  为空或解析失败时降级为固定 1 分钟的 SimpleTrigger，允许为 null
     * @param params 透传给数据源的读取参数映射，取自 YAML {@code params}；键保持配置文件的书写顺序，
     *               值允许为 null（YAML 中 {@code key:} 无值即产生 null），整体恒非 null，缺项时为空映射
     * @param filePath 待读取的单个源文件路径，取自 YAML {@code filePath}，恒非空，不允许为 null
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
     *
     * <p>由 {@link YamlConfigFileParser#mapToConfig(Map)} 在 {@code directoryPath} 与
     * {@code filePath} 都为空、但 {@code text} 非 null 时构造，是本解析器内
     * {@link TextConfigDefinition} 的唯一实现，用于「直接把一段内联文本当作数据源」的
     * 场景；其标识、批量、调度与参数组件的语义与 {@link DefaultDirectoryConfigDefinition}
     * 完全一致，仅数据源位置由路径换成内联文本。</p>
     *
     * <p>可空性同样由唯一构造点 {@code mapToConfig} 决定：标识类组件经 {@code getString} 读取，
     * YAML 缺项时为 null 且紧凑构造器未校验；{@code mappings} 与 {@code params} 由
     * {@code mapToConfig} 现地新建容器，恒非 null；{@code text} 的判断条件是
     * {@code text != null}（非 {@code isNotEmpty}），因此允许为空串，但不允许为 null。</p>
     *
     * @param mappingId 映射标识，取自 YAML 顶层 {@code mappingId}，源配置内唯一；调度器以它作为
     *                   trigger 缓存与上次执行时刻的键，缺项时为 null
     * @param inputId 输入标识（数据入口名），取自 YAML {@code inputId}；映射管理器以其反查关联映射，
     *                缺项时为 null
     * @param sourceId 输入源实例标识，取自 YAML {@code sourceId}；执行时据此解析出
     *                 {@code DataSyncAgentSource}，其方向必须为 {@code INPUT}，缺项时为 null
     * @param outputId 输出标识（响应式通道名），取自 YAML {@code outputId}；作为
     *                 {@code ReactorDataSyncExecutor} 的查找键，并与 {@code sinkId} 拼成订阅去重键，缺项时为 null
     * @param sinkId 输出 sink 实例标识，取自 YAML {@code sinkId}；其方向必须为 {@code OUTPUT}，缺项时为 null
     * @param mappings 字段映射列表，来源于 YAML {@code mappings} 列表；恒非 null，未配置时为空列表，
     *                 元素为非 null 的 {@link DataSyncFieldMapping} 实现
     * @param batch 每批提交给执行器的记录条数（单位：条），取自 YAML {@code batch}，兼容数字与数字字符串，
     *              缺项或解析失败时取 100；小于等于 0 时由调度器改用其默认批量值
     * @param cronType 定时类型标识，取自 YAML {@code cronType}（示例值 {@code fixed}）；当前调度器只依据
     *                 {@code cron} 判定触发时机，此值仅原样透传，允许为 null
     * @param cron Quartz 风格 cron 表达式，取自 YAML {@code cron}（示例 {@code "0/5 * * * * ?"}）；
     *                  为空或解析失败时降级为固定 1 分钟的 SimpleTrigger，允许为 null
     * @param params 透传给数据源的读取参数映射，取自 YAML {@code params}；键保持配置文件的书写顺序，
     *               值允许为 null（YAML 中 {@code key:} 无值即产生 null），整体恒非 null，缺项时为空映射
     * @param text 内联的源文本内容，取自 YAML {@code text}；不为 null，但允许为空串
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
     *
     * <p>{@link YamlConfigFileParser#mapToConfig(Map)} 的兜底分支：当 YAML 未提供
     * {@code directoryPath}、{@code filePath}、{@code text} 中的任何一个时构造，
     * 直接实现 {@link DataSyncConfigDefinition} 而不带数据源位置组件。它也是本文件内唯一
     * 没有 {@code mappingId} 组件的实现——兜底场景下配置只剩通道级信息，映射标识缺失。</p>
     *
     * <p>可空性由唯一构造点 {@code mapToConfig} 决定：四个标识组件一律经 {@code getString} 读取，
     * YAML 缺项时为 null 且紧凑构造器未校验；{@code mappings} 与 {@code params} 由
     * {@code mapToConfig} 现地新建容器，恒非 null（可能为空容器）。</p>
     *
     * @param inputId 输入标识（数据入口名），取自 YAML {@code inputId}；映射管理器以其反查关联映射，
     *                缺项时为 null
     * @param sourceId 输入源实例标识，取自 YAML {@code sourceId}；执行时据此解析出
     *                 {@code DataSyncAgentSource}，其方向必须为 {@code INPUT}，缺项时为 null
     * @param outputId 输出标识（响应式通道名），取自 YAML {@code outputId}；作为
     *                 {@code ReactorDataSyncExecutor} 的查找键，并与 {@code sinkId} 拼成订阅去重键，缺项时为 null
     * @param sinkId 输出 sink 实例标识，取自 YAML {@code sinkId}；其方向必须为 {@code OUTPUT}，缺项时为 null
     * @param mappings 字段映射列表，来源于 YAML {@code mappings} 列表；恒非 null，未配置时为空列表，
     *                 元素为非 null 的 {@link DataSyncFieldMapping} 实现
     * @param batch 每批提交给执行器的记录条数（单位：条），取自 YAML {@code batch}，兼容数字与数字字符串，
     *              缺项或解析失败时取 100；小于等于 0 时由调度器改用其默认批量值
     * @param cronType 定时类型标识，取自 YAML {@code cronType}（示例值 {@code fixed}）；当前调度器只依据
     *                 {@code cron} 判定触发时机，此值仅原样透传，允许为 null
     * @param cron Quartz 风格 cron 表达式，取自 YAML {@code cron}（示例 {@code "0/5 * * * * ?"}）；
     *                  为空或解析失败时降级为固定 1 分钟的 SimpleTrigger，允许为 null
     * @param params 透传给数据源的读取参数映射，取自 YAML {@code params}；键保持配置文件的书写顺序，
     *               值允许为 null（YAML 中 {@code key:} 无值即产生 null），整体恒非 null，缺项时为空映射
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
