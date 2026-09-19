package com.chua.filesystem.support.config.parser;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.config.parser.ConfigParser;
import com.chua.common.support.config.source.MapPropertySource;
import com.chua.common.support.config.source.PropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * TOML 配置解析器
 * <p>
 * 基于 Jackson 的 jackson-dataformat-toml 实现，支持 TOML 格式的配置文件解析。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"toml"})
public class TomlConfigParser implements ConfigParser {

    /**
     * Toml_映射器
    */
    private static final ObjectMapper TOML_MAPPER = new ObjectMapper(new TomlFactory());

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 解析
     *
     * @param urlPath url路径
     * @param is 是否
     * @return 解析的结果
     */
    public PropertySource parse(String urlPath, InputStream is) {
        try {
            Map<String, Object> map = TOML_MAPPER.readValue(is, Map.class);
 // 扁平化嵌套映射
            Map<String, Object> flatMap = flattenMap(map, "");
            return new MapPropertySource(urlPath, flatMap);
        } catch (Exception e) {
            log.error("[filesystem-parser] 解析 TOML 配置文件失败: {}", e.getMessage());
            return PropertySource.EMPTY;
        }
    }

    /**
     * 扁平化嵌套映射
     *
     * @param map    原始映射
     * @param prefix 前缀
     * @return 扁平化后的Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenMap(Map<String, Object> map, String prefix) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                result.putAll(flattenMap((Map<String, Object>) value, key));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }
}

