package com.chua.filesystem.support.ini;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;


/**
* INI 文件解析工具类
* <p>
* 提供通用的 INI 格式文件解析功能
* 支持 Section 和 key=value 对的解析
* 支持注释处理（;和#）
*
* @author CH
* @版本 1.0.0
* @since 4.0.0.42
 */
@Slf4j
public class IniParser {

    /**
    * 私有构造方法，防止实例化
    */
    private IniParser() {
    }

    /**
    * 从输入流中解析 INI 内容
    *
    * @param inputStream INI 输入流
    * @return 解析后的属性映射
    * @throws IOException IO 异常
    */
    public static Properties parseInputStream(InputStream inputStream) throws IOException {
        String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        return parseContent(content);
    }

    /**
    * 解析 INI 内容字符串
    *
    * @param content INI 内容
    * @return 解析后的属性映射
    */
    public static Properties parseContent(String content) {
        Properties properties = new Properties();
        parseToMap(content, properties, "");
        return properties;
    }

    /**
    * 解析 INI 内容为嵌套 映射 结构
    *
    * @param content INI 内容
    * @return 解析后的 映射（Section -> 属性映射）
    */
    public static Map<String, Map<String, String>> parseToNestedMap(String content) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        String[] lines = content.split("\n");
        String currentSection = "";

        for (String line : lines) {
            line = line.trim();

            // 跳过空行和注释行
            if (line.isEmpty() || isComment(line)) {
                continue;
            }

            // 检查是否为 Section 标题
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                result.putIfAbsent(currentSection, new LinkedHashMap<>());
            } else if (line.contains("=")) {
                // 解析 Key=Value 对
                int delimiterIndex = line.indexOf('=');
                String key = line.substring(0, delimiterIndex).trim();
                String value = line.substring(delimiterIndex + 1).trim();

                if (!currentSection.isEmpty()) {
                    result.get(currentSection).put(key, value);
                } else {
                    // 如果还没有 Section，使用默认 Section
                    result.putIfAbsent("", new LinkedHashMap<>());
                    result.get("").put(key, value);
                }
            }
        }

        return result;
    }

    /**
    * 解析 INI 内容为列表格式
    * 每个 Section 作为一个 映射 项
    *
    * @param content INI 内容
    * @return Section 列表
    */
    public static List<Map<String, String>> parseToList(String content) {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, Map<String, String>> nestedMap = parseToNestedMap(content);

        for (Map.Entry<String, Map<String, String>> entry : nestedMap.entrySet()) {
            Map<String, String> section = new LinkedHashMap<>(entry.getValue());
            section.put("__section__", entry.getKey());
            result.add(section);
        }

        return result;
    }

    /**
    * 检查行是否为注释
    *
    * @param line 行内容
    * @return 是否为注释
    */
    private static boolean isComment(String line) {
        return line.startsWith(";") || line.startsWith("#");
    }

    /**
    * 解析内容到 属性 对象，支持 Section 前缀
    *
    * @param content    INI 内容
    * @param properties 属性 对象
    * @param prefix     前缀
    */
    private static void parseToMap(String content, Properties properties, String prefix) {
        String[] lines = content.split("\n");
        String currentSection = "";

        for (String line : lines) {
            line = line.trim();

            // 跳过空行和注释行
            if (line.isEmpty() || isComment(line)) {
                continue;
            }

            // 检查是否为 Section 标题
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                // 添加 Section 标记
                if (!prefix.isEmpty()) {
                    properties.put(prefix + ".__section__." + currentSection, currentSection);
                } else {
                    properties.put("__section__." + currentSection, currentSection);
                }
            } else if (line.contains("=")) {
                // 解析 Key=Value 对
                int delimiterIndex = line.indexOf('=');
                String key = line.substring(0, delimiterIndex).trim();
                String value = line.substring(delimiterIndex + 1).trim();

                // 构建完整的键名
                String fullKey;
                if (!currentSection.isEmpty()) {
                    fullKey = currentSection + "." + key;
                } else {
                    fullKey = key;
                }

                if (!prefix.isEmpty()) {
                    fullKey = prefix + "." + fullKey;
                }

                properties.put(fullKey, value);
            }
        }
    }

    /**
    * 获取 INI 的所有 Section 名称
    *
    * @param content INI 内容
    * @return Section 名称集合
    */
    public static Set<String> getSections(String content) {
        Set<String> sections = new LinkedHashSet<>();
        String[] lines = content.split("\n");

        for (String line : lines) {
            line = line.trim();

            // 检查是否为 Section 标题
            if (line.startsWith("[") && line.endsWith("]")) {
                String sectionName = line.substring(1, line.length() - 1).trim();
                sections.add(sectionName);
            }
        }

        return sections;
    }

    /**
    * 从 属性 对象转换回 INI 格式字符串
    *
    * @param properties 属性 对象
    * @return INI 格式字符串
    */
    public static String propertiesToIni(Properties properties) {
        StringBuilder sb = new StringBuilder();
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();

        // 首先按 Section 分组
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("__section__.")) {
                continue;
            }

            String[] parts = key.split("\\.");
            if (parts.length >= 2) {
                String section = parts[0];
                String propKey = key.substring(section.length() + 1);
                sections.putIfAbsent(section, new LinkedHashMap<>());
                sections.get(section).put(propKey, properties.getProperty(key));
            } else {
                // 全局属性
                sections.putIfAbsent("", new LinkedHashMap<>());
                sections.get("").put(key, properties.getProperty(key));
            }
        }

        // 生成 INI 格式
        for (Map.Entry<String, Map<String, String>> section : sections.entrySet()) {
            String sectionName = section.getKey();

            // 添加 Section 标题（除了全局 Section）
            if (!sectionName.isEmpty()) {
                sb.append("[").append(sectionName).append("]\n");
            }

            // 添加键值对
            for (Map.Entry<String, String> entry : section.getValue().entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }
}
