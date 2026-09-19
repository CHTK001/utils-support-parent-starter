package com.chua.common.support.config.parser;

import com.chua.common.support.config.source.MapPropertySource;
import com.chua.common.support.config.source.PropertiesMutiPropertySource;
import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.IoUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;


/**
 * JSON/JSON5 config parser
 * <p>
 * Supports JSON5 features:
 * <ul>
 *     <li>Single-line and multi-line comments</li>
 *     <li>Trailing commas</li>
 *     <li>Single quoted strings</li>
 *     <li>Hexadecimal numbers</li>
 *     <li>Unquoted object keys</li>
 * </ul>
 *
 * @author CH
 * @since 2023-09-05
 */
@Slf4j
@Spi({"json", "json5"})
public class JsonConfigParser implements ConfigParser {

    @Override
    /**
     * 解析
    */
    public PropertySource parse(String urlPath, InputStream is) {
        try {
            String content = IoUtils.asString(is, StandardCharsets.UTF_8);
            String trimmed = content.trim();

            // Check if List or Object
            if (trimmed.startsWith("[")) {
                // List format, use MutiPropertySource
                List<Object> list = Json.fromJson(trimmed, List.class);
                return new PropertiesMutiPropertySource(urlPath, list);
            } else {
                // Object format
                Map<String, Object> map = Json.fromJson(trimmed, Map.class);
                return new MapPropertySource(urlPath, map);
            }
        } catch (Exception e) {
            log.error("Failed to parse JSON/JSON5 config file: {}", e.getMessage());
            return PropertySource.EMPTY;
        }
    }
}
