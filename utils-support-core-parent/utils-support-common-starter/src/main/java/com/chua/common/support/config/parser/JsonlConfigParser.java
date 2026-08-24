package com.chua.common.support.config.parser;

import com.chua.common.support.config.source.MapPropertySource;
import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.IoUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSONL (JSON Lines) 配置文件解析器
 * <p>
 * JSONL 格式每行一个独立 JSON 对象，逐行解析后合并为统一 PropertySource。
 * 支持空行和以 // 开头的注释行自动跳过。
 * </p>
 *
 * <pre>{@code
 * {"server.port": 8080}
 * {"server.host": "localhost"}
 * {"db.url": "jdbc:h2:mem:test"}
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jsonl")
public class JsonlConfigParser implements ConfigParser {

    @Override
    public PropertySource parse(String urlPath, InputStream is) {
        try {
            String content = IoUtils.asString(is, StandardCharsets.UTF_8);
            Map<String, Object> merged = new LinkedHashMap<>();

            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                    continue;
                }
                try {
                    Map<String, Object> lineMap = Json.fromJson(trimmed, Map.class);
                    if (lineMap != null) {
                        merged.putAll(lineMap);
                    }
                } catch (Exception e) {
                    log.debug("Skipping invalid JSONL line: {}", trimmed);
                }
            }
            return new MapPropertySource(urlPath, merged);
        } catch (Exception e) {
            log.error("Failed to parse JSONL config file: {}", e.getMessage());
            return PropertySource.EMPTY;
        }
    }
}
