package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.DecisionNode;
import com.chua.common.support.task.pipeline.node.TaskNode;

import java.util.*;

/**
 * JSON 流水线解析器。
 *
 * <p>从 JSON 字符串解析并构建 {@link PipelineBuilder}，支持声明式定义流水线结构。</p>
 *
 * <p><strong>JSON 格式规范：</strong></p>
 * <pre>{@code
 * {
 *   "id": "my-pipeline",          // 可选，流水线唯一标识，默认自动生成
 *   "start": "node1",             // 可选，起始节点 ID，默认为 nodes 数组中第一个节点
 *   "end": "node3",               // 可选，终止节点 ID
 *   "nodes": [                    // 必需，节点数组，按添加顺序排列
 *     {
 *       "id": "node1",            // 必需，节点唯一标识
 *       "type": "task",           // 必需，节点类型：task / decision
 *       "params": {               // 可选，节点参数，执行时注入到 ctx.nodeLocalData
 *         "timeout": 5000,
 *         "retries": 3
 *       }
 *     },
 *     {
 *       "id": "node2",
 *       "type": "decision",       // decision 类型需配合 branches
 *       "params": {
 *         "threshold": 0.8
 *       },
 *       "branches": {             // decision 可选，分支映射（用于可视化）
 *         "true": "node3",        // key 为分支标签（如 "true"/"false" 或自定义名称）
 *         "false": "node1"        // value 为目标节点 ID
 *       }
 *     },
 *     {
 *       "id": "node3",
 *       "type": "task",
 *       "params": {
 *         "outputFormat": "json"
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p><strong>字段说明：</strong></p>
 * <table>
 *   <tr><th>字段</th><th>层级</th><th>必需</th><th>说明</th></tr>
 *   <tr><td>id</td><td>根</td><td>否</td><td>流水线唯一标识</td></tr>
 *   <tr><td>start</td><td>根</td><td>否</td><td>起始节点 ID，默认取 nodes[0].id</td></tr>
 *   <tr><td>end</td><td>根</td><td>否</td><td>终止节点 ID</td></tr>
 *   <tr><td>nodes</td><td>根</td><td>是</td><td>节点数组，至少包含一个节点</td></tr>
 *   <tr><td>id</td><td>node</td><td>是</td><td>节点唯一标识</td></tr>
 *   <tr><td>type</td><td>node</td><td>是</td><td>节点类型：task 或 decision</td></tr>
 *   <tr><td>params</td><td>node</td><td>否</td><td>节点参数对象，执行时注入到 ctx.nodeLocalData</td></tr>
 *   <tr><td>branches</td><td>node</td><td>decision可选</td><td>分支映射，key 为分支标签，value 为目标节点 ID（用于可视化）</td></tr>
 * </table>
 *
 * <p><strong>params 参数注入机制：</strong></p>
 * <p>JSON 中定义的 {@code params} 会在流水线执行时自动注入到节点的 {@code nodeLocalData}，
 * 节点内部可通过 {@code ctx.getNodeLocalValue("key")} 获取。
 * 这使得 JSON 定义的静态配置可以在运行时被节点逻辑读取。</p>
 *
 * <p><strong>注意事项：</strong></p>
 * <ul>
 *   <li>JSON 构建仅支持静态结构定义（节点顺序和分支映射），业务逻辑需通过代码注入</li>
 *   <li>构建后可通过 {@link PipelineBuilder#task(String, PipelineNode)} 等方法覆盖同名节点以注入逻辑</li>
 *   <li>branches 为可选字段，仅用于树打印可视化，不影响路由逻辑（路由由节点回调返回值决定）</li>
 *   <li>params 中的值支持字符串、数字、布尔值和 null</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PipelineJsonParser {

    /**
     * 解析 JSON 字符串并创建 PipelineBuilder。
     *
     * @param json JSON 字符串
     * @return PipelineBuilder 实例
     * @throws IllegalArgumentException 当 JSON 格式不合法时抛出
     */
    public static PipelineBuilder parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON string must not be null or empty");
        }

        JsonNode root = parseJson(json.trim());
        String pipelineId = root.getString("id", "pipeline-" + UUID.randomUUID().toString().substring(0, 8));
        PipelineBuilder builder = PipelineBuilder.newBuilder(pipelineId);

        // 解析 start/end
        String startId = root.getString("start", null);
        String endId = root.getString("end", null);
        if (startId != null) {
            builder.start(startId);
        }
        if (endId != null) {
            builder.end(endId);
        }

        // 解析 nodes 数组
        List<JsonNode> nodes = root.getArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("JSON must contain 'nodes' array with at least one node");
        }

        for (JsonNode node : nodes) {
            String nodeId = node.getString("id", null);
            if (nodeId == null || nodeId.isEmpty()) {
                throw new IllegalArgumentException("Each node must have an 'id' field");
            }

            String type = node.getString("type", "task");
            // 解析节点参数（可选）
            Map<String, Object> params = node.getObjectMap("params");

            switch (type) {
                case "task": {
                    // 创建占位 TaskNode（回调返回 null，后续可通过代码覆盖注入逻辑）
                    TaskNode taskNode = new TaskNode(nodeId, ctx -> null);
                    if (params != null && !params.isEmpty()) {
                        taskNode.setParams(params);
                    }
                    builder.getNodes().add(taskNode);
                    builder.getNodeMap().put(nodeId, taskNode);
                    break;
                }
                case "decision": {
                    // 解析 branches（可选，用于可视化）
                    Map<String, String> branches = node.getObjectAsStringMap("branches");
                    // 创建占位 DecisionNode（回调返回 null，后续可通过代码覆盖注入路由逻辑）
                    DecisionNode decisionNode = new DecisionNode(nodeId, ctx -> null);
                    if (branches != null && !branches.isEmpty()) {
                        decisionNode.branches(branches);
                    }
                    if (params != null && !params.isEmpty()) {
                        decisionNode.setParams(params);
                    }
                    // 手动添加到 builder
                    builder.getNodes().add(decisionNode);
                    builder.getNodeMap().put(nodeId, decisionNode);
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unknown node type: " + type);
            }
        }

        return builder;
    }

    // ========== 简易 JSON 解析器（无外部依赖） ==========

    /**
     * 简易 JSON 节点表示
     */
    static class JsonNode {
        /** data */
        private final Map<String, Object> data;

        JsonNode(Map<String, Object> data) {
            this.data = data != null ? data : new LinkedHashMap<>();
        }

        String getString(String key, String defaultValue) {
            Object val = data.get(key);
            return val instanceof String ? (String) val : defaultValue;
        }

        List<JsonNode> getArray(String key) {
            Object val = data.get(key);
            if (val instanceof List) {
                @SuppressWarnings("unchecked")
                List<JsonNode> result = (List<JsonNode>) val;
                return result;
            }
            return null;
        }

        /**
        * 获取对象值（String -> String 映射）。
        * 自动将值转为字符串表示。
        *
        * @param key 属性键
        * @return 字符串映射，不存在时返回 null
        */
        Map<String, String> getObjectAsStringMap(String key) {
            Object val = data.get(key);
            if (val instanceof Map) {
                Map<String, String> result = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) val;
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    result.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : null);
                }
                return result;
            }
            return null;
        }

        /**
         * 获取对象值（支持任意值类型）。
         *
         * @param key 属性键
         * @return 任意值类型的映射，不存在时返回 null
         */
        Map<String, Object> getObjectMap(String key) {
            Object val = data.get(key);
            if (val instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) val;
                return result;
            }
            return null;
        }
    }

    /**
     * 简易 JSON 解析（支持对象、数组、字符串、布尔值、数字、null）
     * @param json 方法入参 json
     * @return Json节点 对象
     */
    static JsonNode parseJson(String json) {
        Object result = parseValue(json.trim(), new int[]{0});
        if (result instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            return new JsonNode(map);
        }
        throw new IllegalArgumentException("JSON root must be an object");
    }

    /**
     * 解析Value
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     * @return 对象 对象
     */
    private static Object parseValue(String json, int[] pos) {
        skipWhitespace(json, pos);
        if (pos[0] >= json.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        char c = json.charAt(pos[0]);
        if (c == '{') {
            return parseObject(json, pos);
        } else if (c == '[') {
            return parseArray(json, pos);
        } else if (c == '"') {
            return parseString(json, pos);
        } else if (c == 't' || c == 'f') {
            return parseBoolean(json, pos);
        } else if (c == 'n') {
            return parseNull(json, pos);
        } else if (c == '-' || Character.isDigit(c)) {
            return parseNumber(json, pos);
        }
        throw new IllegalArgumentException("Unexpected character at position " + pos[0] + ": " + c);
    }

    /**
     * 解析Object
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     * @return 结果映射，无数据时为空映射
     */
    private static Map<String, Object> parseObject(String json, int[] pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        // 跳过 '{'
        pos[0]++;
        skipWhitespace(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == '}') {
            pos[0]++;
            return map;
        }
        while (pos[0] < json.length()) {
            skipWhitespace(json, pos);
            String key = parseString(json, pos);
            skipWhitespace(json, pos);
            expectChar(json, pos, ':');
            Object value = parseValue(json, pos);
            map.put(key, value);
            skipWhitespace(json, pos);
            if (pos[0] < json.length() && json.charAt(pos[0]) == ',') {
                pos[0]++;
            } else if (pos[0] < json.length() && json.charAt(pos[0]) == '}') {
                pos[0]++;
                break;
            }
        }
        return map;
    }

    /**
     * 解析Array
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     * @return 结果列表，无数据时为空列表
     */
    private static List<Object> parseArray(String json, int[] pos) {
        List<Object> list = new ArrayList<>();
        // 跳过 '['
        pos[0]++;
        skipWhitespace(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == ']') {
            pos[0]++;
            return list;
        }
        while (pos[0] < json.length()) {
            list.add(parseValue(json, pos));
            skipWhitespace(json, pos);
            if (pos[0] < json.length() && json.charAt(pos[0]) == ',') {
                pos[0]++;
            } else if (pos[0] < json.length() && json.charAt(pos[0]) == ']') {
                pos[0]++;
                break;
            }
        }
        return list;
    }

    /**
     * 解析String
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     * @return 结果字符串
     */
    private static String parseString(String json, int[] pos) {
        skipWhitespace(json, pos);
        if (json.charAt(pos[0]) != '"') {
            throw new IllegalArgumentException("Expected '\"' at position " + pos[0]);
        }
        // 跳过起始双引号
        pos[0]++;
        StringBuilder sb = new StringBuilder();
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]);
            if (c == '\\') {
                pos[0]++;
                if (pos[0] < json.length()) {
                    char escaped = json.charAt(pos[0]);
                    switch (escaped) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        default: sb.append(escaped);
                    }
                }
            } else if (c == '"') {
                pos[0]++;
                return sb.toString();
            } else {
                sb.append(c);
            }
            pos[0]++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    /**
     * 解析Boolean
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     * @return Boolean 对象
     */
    private static Boolean parseBoolean(String json, int[] pos) {
        if (json.startsWith("true", pos[0])) {
            pos[0] += 4;
            return Boolean.TRUE;
        } else if (json.startsWith("false", pos[0])) {
            pos[0] += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid boolean at position " + pos[0]);
    }

    /**
     * 解析Null
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     * @return 对象 对象
     */
    private static Object parseNull(String json, int[] pos) {
        if (json.startsWith("null", pos[0])) {
            pos[0] += 4;
            return null;
        }
        throw new IllegalArgumentException("Invalid null at position " + pos[0]);
    }

    /**
     * 解析Number
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     * @return Number 对象
     */
    private static Number parseNumber(String json, int[] pos) {
        int start = pos[0];
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]);
            if (Character.isDigit(c) || c == '-' || c == '.' || c == 'e' || c == 'E' || c == '+') {
                pos[0]++;
            } else {
                break;
            }
        }
        String numStr = json.substring(start, pos[0]);
        if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
            return Double.parseDouble(numStr);
        }
        return Long.parseLong(numStr);
    }

    /**
     * 跳过Whitespace
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     */
    private static void skipWhitespace(String json, int[] pos) {
        while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    /**
     * ExpectChar
     * @param json 方法入参 json
     * @param pos 方法入参 pos
     * @param expected 方法入参 expected
     */
    private static void expectChar(String json, int[] pos, char expected) {
        if (pos[0] >= json.length() || json.charAt(pos[0]) != expected) {
            throw new IllegalArgumentException(
                    "Expected '" + expected + "' at position " + pos[0]);
        }
        pos[0]++;
    }
}
