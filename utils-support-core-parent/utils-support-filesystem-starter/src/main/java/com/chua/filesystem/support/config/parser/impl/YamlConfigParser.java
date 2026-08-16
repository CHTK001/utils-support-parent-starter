package com.chua.filesystem.support.config.parser.impl;

import com.chua.common.support.config.parser.ConfigParser;
import com.chua.common.support.config.source.MapPropertySource;
import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML 配置文件解析器。
 *
 * <p>解析 YAML 格式的配置文件（.yaml 或 .yml 后缀）。
 * 将 YAML 的层级结构展平为点分隔的 key-value 对。</p>
 *
 * <p>解析规则：
 * <ul>
 *   <li>支持 .yaml 和 .yml 两种文件扩展名</li>
 *   <li>嵌套 Map 使用 '.' 连接，形成全路径 key</li>
 *   <li>List 使用 [index] 索引作为 key 的一部分</li>
 *   <li>基础类型（字符串、数字、布尔值）直接作为 value</li>
 * </ul></p>
 *
 * <p>示例：</p>
 * <pre>
 *   server:
 *     port: 8080
 *     host: localhost
 *   app:
 *     name: my-app
 *     profiles:
 *       - dev
 *       - test
 *   // 解析结果：
 *   // server.port = 8080
 *   // server.host = "localhost"
 *   // app.name = "my-app"
 *   // app.profiles[0] = "dev"
 *   // app.profiles[1] = "test"
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("yaml")
@SpiDescribe("YAML 配置文件解析器")
public class YamlConfigParser implements ConfigParser {


    @Override
    public PropertySource parse(String urlPath, InputStream is) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Yaml yaml = new Yaml();
            result = yaml.load(is);
            log.debug("[filesystem-impl] 解析 YAML 配置成功，共 {} 项", result.size());
        } catch (Exception e) {
            log.warn("[filesystem-impl] 解析 YAML 配置失败: {}", urlPath, e);
        }
        return new MapPropertySource(urlPath, result);
    }
}
