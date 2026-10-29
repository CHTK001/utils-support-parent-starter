package com.chua.duckduckgo.support;

import com.chua.common.support.lang.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Duck.ai 的 SSE 解析器
 *
 * <p>上游每条事件形如 {@code data: {"message":"..."}}，以 {@code data: [DONE]} 结束；
 * 出错时返回 {@code {"action":"error","status":418,"type":"ERR_BN_LIMIT"}}。</p>
 *
 * @author CHUA
 */
final class DuckStreamParser {

    /**
     * 文本回调
     */
    private final DuckBrowserTransport.DuckStreamListener listener;

    /**
     * 未处理完的缓冲
     */
    private final StringBuilder buffer = new StringBuilder();

    /**
     * 错误事件原文，无错误时为 null
     */
    private String error;

    /**
     * 构造解析器
     *
     * @param listener 文本回调
     */
    DuckStreamParser(DuckBrowserTransport.DuckStreamListener listener) {
        this.listener = listener;
    }

    /**
     * 追加分片
     *
     * @param chunk SSE 分片
     */
    void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        buffer.append(chunk);
        int index;
        while ((index = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, index).trim();
            buffer.delete(0, index + 1);
            handle(line);
        }
    }

    /**
     * 处理剩余内容
     */
    void flush() {
        if (buffer.length() > 0) {
            String line = buffer.toString().trim();
            buffer.setLength(0);
            handle(line);
        }
    }

    /**
     * 解析单行
     *
     * @param line 行内容
     */
    private void handle(String line) {
        if (line.isEmpty() || !line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return;
        }
        Map<String, Object> event;
        try {
            event = Json.fromJson(data);
        } catch (RuntimeException e) {
            return;
        }
        if (event == null) {
            return;
        }
        if ("error".equals(String.valueOf(event.get("action")))) {
            error = data;
            return;
        }
        Object type = event.get("type");
        String typeValue = type == null ? null : String.valueOf(type);
        Object role = event.get("role");
        String roleValue = role == null ? null : String.valueOf(role);
        if ("generated-image".equals(typeValue) || "generated-image".equals(roleValue)) {
            handleImage(event);
            return;
        }
        if ("partial-image".equals(typeValue) || "partial-image".equals(roleValue)) {
            handleImage(event);
            return;
        }
        if ("ui-component".equals(typeValue)) {
            handleUiComponent(event);
            return;
        }
        if ("tool-invocation".equals(roleValue) || "tool-invocation".equals(typeValue)) {
            handleToolInvocation(event);
            return;
        }
        if ("error".equals(event.get("error"))) {
            error = data;
            return;
        }
        Object message = event.get("message");
        if (message != null && "assistant".equals(roleValue)) {
            listener.onText(String.valueOf(message));
        }
    }

    /**
     * 解析 ui-component 图片卡片
     *
     * <p>前端把 {@code generated-image} 折叠成
     * {@code type=ui-component, name=generate-image} 的卡片，
     * 成功卡片的 data 里有 {@code b64Image}；失败卡片只回错误文本。</p>
     *
     * @param event 卡片事件
     */
    private void handleUiComponent(Map<String, Object> event) {
        Object name = event.get("name");
        if (!"generate-image".equals(String.valueOf(name))) {
            return;
        }
        Map<String, Object> data = asMap(event.get("data"));
        if (data == null) {
            return;
        }
        String status = stringValue(data.get("status"));
        if ("error".equals(status)) {
            String reason = stringValue(data.get("error"));
            String detail = reason == null || reason.isBlank() ? "image_generation_failed" : reason;
            listener.onText("\n[Image generation failed: " + detail + "]\n");
            return;
        }
        if (!"success".equals(status)) {
            return;
        }
        handleImage(data);
    }

    /**
     * 解析工具调用事件
     *
     * <p>{@code tool-invocation} + {@code toolName=GenerateImage} 时，
     * 把 {@code toolArguments.imageGenPrompt} 作为文本回传，便于日志与历史。</p>
     *
     * @param event 工具事件
     */
    private void handleToolInvocation(Map<String, Object> event) {
        if (!"GenerateImage".equals(stringValue(event.get("toolName")))) {
            return;
        }
        String argumentText = describeToolArguments(stringValue(event.get("toolArguments")));
        if (!argumentText.isEmpty()) {
            listener.onText(argumentText);
        }
    }

    /**
     * 解析图片数据节点
     *
     * <p>字段既可能在事件顶层，也可能挂在 {@code data} 下，
     * 且部分前端把 base64 放在 {@code result} 字段（见 {@code b64Image:s} 折叠逻辑）。</p>
     *
     * @param source 数据节点
     */
    private void handleImage(Map<String, Object> source) {
        if (source == null) {
            return;
        }
        Map<String, Object> lookup = source;
        Map<String, Object> dataNode = asMap(source.get("data"));
        if (dataNode != null) {
            lookup = dataNode;
        }
        String b64Image = stringValue(lookup.get("b64Image"));
        if (b64Image == null || b64Image.isEmpty()) {
            b64Image = stringValue(source.get("b64Image"));
        }
        if (b64Image == null || b64Image.isEmpty()) {
            b64Image = stringValue(lookup.get("result"));
        }
        if (b64Image == null || b64Image.isEmpty()) {
            b64Image = stringValue(source.get("result"));
        }
        if (b64Image == null || b64Image.isEmpty()) {
            return;
        }
        String format = stringValue(lookup.get("format"));
        int width = intValue(lookup.get("width"));
        int height = intValue(lookup.get("height"));
        listener.onImage(b64Image, format, width, height);
    }

    /**
     * 安全转换 Map
     *
     * @param raw 原始值
     * @return 目标 Map；不是 Map 返回 null
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    /**
     * 把工具参数转成可读文本
     *
     * <p>优先解析 {@code imageGenPrompt}；解析失败时退回原始 JSON，避免信息丢失。</p>
     *
     * @param toolArguments 工具参数原文
     * @return 可读文本；无内容返回空串
     */
    private String describeToolArguments(String toolArguments) {
        try {
            Map<String, Object> arguments = Json.fromJson(toolArguments);
            if (arguments == null) {
                return "";
            }
            String prompt = stringValue(arguments.get("imageGenPrompt"));
            if (prompt != null && !prompt.trim().isEmpty()) {
                return prompt.trim();
            }
        } catch (RuntimeException ignored) {
            // 参数不是 JSON 时直接按原文返回
        }
        return toolArguments.trim();
    }

    /**
     * 读取字符串值
     *
     * @param value 原始值
     * @return 字符串；null 原样返回
     */
    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value);
        return "null".equals(result) ? null : result;
    }

    /**
     * 读取整型值
     *
     * @param value 原始值
     * @return 整型；缺失返回 0
     */
    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 错误事件原文
     *
     * @return 错误原文；无错误返回 null
     */
    String error() {
        return error;
    }

    /**
     * 解析错误事件为可读描述
     *
     * @return 错误描述
     */
    String describeError() {
        if (error == null) {
            return "";
        }
        try {
            Map<String, Object> event = Json.fromJson(error);
            if (event == null) {
                return error;
            }
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("status", event.get("status"));
            description.put("type", event.get("type"));
            description.put("overrideCode", event.get("overrideCode"));
            return Json.toJson(description);
        } catch (RuntimeException e) {
            return error;
        }
    }
}
