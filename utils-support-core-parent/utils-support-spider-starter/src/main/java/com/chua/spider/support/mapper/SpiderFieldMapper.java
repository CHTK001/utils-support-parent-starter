package com.chua.spider.support.mapper;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.utils.StringUtils;
import com.chua.spider.support.annotation.SpiderAi;
import com.chua.spider.support.annotation.SpiderField;
import com.chua.spider.support.model.SpiderResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 爬虫字段映射器。
 *
 * <p>将 {@link SpiderResult} 中的 HTML 或文本内容，
 * 根据 {@link SpiderField} 和 {@link SpiderAi} 注解的配置，
 * 自动映射到 POJO 对象的字段上。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@ConditionalOnClass({"org.jsoup.Jsoup", "com.fasterxml.jackson.databind.ObjectMapper"})
public class SpiderFieldMapper {

    /** 映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 最大_AI_文本_长度 */
    private static final int MAX_AI_TEXT_LENGTH = 8000;

    /** 对话客户端 */
    private final ChatClient chatClient;

    /**
     * 构造器（不使用 AI 提取）。
     */
    public SpiderFieldMapper() {
        this.chatClient = null;
    }

    /**
     * 构造器（使用 AI 提取）。
     *
     * @param aiProvider AI 服务商名称，如 "openai"、"deepseek"
     * @param aiApiKey   API 键
     */
    public SpiderFieldMapper(String aiProvider, String aiApiKey) {
        if (aiProvider != null && aiApiKey != null) {
            this.chatClient = ChatClient.create(
                    ChatClientSetting.builder()
                            .provider(aiProvider)
                            .appKey(aiApiKey)
                            .build());
        } else {
            this.chatClient = null;
        }
    }

    /**
     * 将 蜘蛛结果 映射到指定类型的 POJO。
     * @param result 结果
     * @param clazz clazz
     * @return 映射的结果
     */
    @SuppressWarnings("unchecked")
    public <T> T map(SpiderResult result, Class<T> clazz) {
        try {
            T instance = ReflectUtils.instantiate(clazz);
            Map<String, String> aiFields = new LinkedHashMap<>();

            for (Field field : clazz.getDeclaredFields()) {
                SpiderField annotation = field.getAnnotation(SpiderField.class);
                if (annotation == null) {
                    continue;
                }
                String value = null;

                // 方式1：CSS 选择器提取（优先级高）
                if (!annotation.selector().isEmpty()) {
                    value = extractBySelector(result.getHtml(), annotation);
                }

                // 方式2：AI 提取（当 selector 未设置时）
                if (value == null && !annotation.ai().isEmpty()) {
                    aiFields.put(field.getName(), annotation.ai());
                }

                if (value != null) {
                    setFieldValue(instance, field, value);
                } else if (!annotation.defaultValue().isEmpty()) {
                    setFieldValue(instance, field, annotation.defaultValue());
                }
            }

            // AI 批量提取
            if (!aiFields.isEmpty() && chatClient != null) {
                Map<String, String> aiResults = extractByAi(result, clazz, aiFields);
                for (Map.Entry<String, String> entry : aiResults.entrySet()) {
                    Field field = ReflectUtils.findField(clazz, entry.getKey());
                    if (field == null) {
                        continue;
                    }
                    setFieldValue(instance, field, entry.getValue());
                }
            }

            return instance;

        } catch (Exception e) {
            log.error("[spider-mapper] POJO 映射失败: {}", clazz.getName(), e);
            return null;
        }
    }

    /**
     * 通过 CSS 选择器从 HTML 中提取值。
     * @param html HTML
     * @param annotation 注解
     * @return extractBySelector的结果
     */
    private String extractBySelector(String html, SpiderField annotation) {
        if (StringUtils.isEmpty(html)) {
            return null;
        }
        try {
            Document doc = Jsoup.parse(html);
            Elements elements = doc.select(annotation.selector());
            if (elements.isEmpty()) {
                return null;
            }
            String attr = annotation.attr();
            switch (attr) {
                case "text": return elements.first().text();
                case "html": return elements.first().html();
                default: return elements.first().attr(attr);
            }
        } catch (Exception e) {
            log.warn("[spider-mapper] CSS 选择器提取失败: {}", annotation.selector(), e);
            return null;
        }
    }

    /**
     * 通过 AI 批量提取字段值。
     */
    private Map<String, String> extractByAi(SpiderResult result, Class<?> clazz,
                                             Map<String, String> aiFields) {
        Map<String, String> results = new LinkedHashMap<>();

        try {
 // 读取 @蜘蛛AI 类级注解
            SpiderAi classAi = clazz.getAnnotation(SpiderAi.class);
            StringBuilder prompt = new StringBuilder();

            if (classAi != null && StringUtils.isNotEmpty(classAi.value())) {
                prompt.append(classAi.value()).append("\n\n");
            }
            prompt.append("从以下文本中提取信息，返回 JSON 格式：\n");
            for (Map.Entry<String, String> entry : aiFields.entrySet()) {
                prompt.append("- ").append(entry.getKey())
                        .append(": ").append(entry.getValue()).append("\n");
            }
            prompt.append("\n只返回 JSON，不要包含其他说明文字。");

 // 调用 对话客户端
            String text = result.getText() != null ? result.getText() : "";
            if (text.length() > MAX_AI_TEXT_LENGTH) {
                text = text.substring(0, MAX_AI_TEXT_LENGTH);
            }

            String response = chatClient.system(prompt.toString()).model(
                    classAi != null && StringUtils.isNotEmpty(classAi.model()) ?
                            classAi.model() : "").chatSync(text);

            if (StringUtils.isEmpty(response)) {
                return results;
            }
            // 优先使用 Jackson 解析 JSON
            parseJsonResponse(response, results);

        } catch (Exception e) {
            log.warn("[spider-mapper] AI 提取失败: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 解析 JSON 响应，优先使用 Jackson，失败时回退到手动解析。
     * @param json json
     * @param results 结果
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void parseJsonResponse(String json, Map<String, String> results) {
        // 清理 JSON 包裹
        json = json.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }

        // 尝试 Jackson 解析
        try {
            Map<String, Object> map = MAPPER.readValue(json, LinkedHashMap.class);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                results.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
            }
            return;
        } catch (Exception e) {
            log.debug("[spider-mapper] Jackson 解析失败，使用手动回退解析", e);
        }

        // 回退：手动解析简单 JSON
        try {
            if (json.startsWith("{")) {
                json = json.substring(1, json.lastIndexOf('}') > 0 ?
                        json.lastIndexOf('}') : json.length()).trim();
            }
            for (String line : json.split("\n")) {
                line = line.trim();
                if (!line.contains(":")) {
                    continue;
                }
                int idx = line.indexOf(":");
                String key = line.substring(0, idx).trim()
                        .replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");
                String val = line.substring(idx + 1).trim()
                        .replaceAll(",$", "")
                        .replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");
                results.put(key, val);
            }
        } catch (Exception e) {
            log.warn("[spider-mapper] JSON 手动解析失败", e);
        }
    }

    /**
     * 设置字段值（支持 字符串 + 基本类型转换）。
     * @param instance instance
     * @param field 字段
     * @param value 值
     */
    private void setFieldValue(Object instance, Field field, String value) {
        try {
            Class<?> type = field.getType();
            if (type == String.class) {
                ReflectUtils.setField(instance, field.getName(), value);
            } else if (type == int.class || type == Integer.class) {
                ReflectUtils.setField(instance, field.getName(), Integer.parseInt(value));
            } else if (type == long.class || type == Long.class) {
                ReflectUtils.setField(instance, field.getName(), Long.parseLong(value));
            } else if (type == double.class || type == Double.class) {
                ReflectUtils.setField(instance, field.getName(), Double.parseDouble(value));
            } else if (type == boolean.class || type == Boolean.class) {
                ReflectUtils.setField(instance, field.getName(), Boolean.parseBoolean(value));
            } else {
                ReflectUtils.setField(instance, field.getName(), value);
            }
        } catch (Exception e) {
            log.warn("[spider-mapper] 字段赋值失败: {}.{}",
                    field.getDeclaringClass().getSimpleName(), field.getName(), e);
        }
    }
}
