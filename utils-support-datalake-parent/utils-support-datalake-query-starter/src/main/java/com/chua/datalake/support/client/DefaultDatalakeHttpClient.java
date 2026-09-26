package com.chua.datalake.support.client;

import com.chua.common.support.lang.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.TypeFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 HTTP客户端 实现，基于 JDK HTTP客户端 调用 api服务端 /query 路由。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultDatalakeHttpClient extends DatalakeHttpClient {

    /**
     * 响应体回显上限（字符）
     */
    private static final int BODY_PREVIEW = 200;

    /**
     * 创建 默认数据湖http客户端 实例
     * @param baseUrl baseurl
     */
    public DefaultDatalakeHttpClient(String baseUrl) {
        super(baseUrl);
    }

    /**
     * 执行查询并解析为对象列表。
     *
     * @param sql  SQL 字符串
     * @param args 绑定参数
     * @return 结果列表
     * @throws Exception 请求或解析失败
     */
    public List<Map<String, Object>> queryAsList(String sql, Object... args) throws Exception {
        return parseRows(queryWithArgs(sql, args));
    }

    /**
     * 带参数校验地发起查询。
     *
     * <p>查询端点只传输 SQL 文本，没有绑定参数通道。参数非空时必须拒绝，
     * 静默丢弃会让上层以为"按参数查过了"。</p>
     *
     * @param sql  SQL 字符串
     * @param args 绑定参数
     * @return 响应体
     * @throws Exception 请求失败
     */
    public String queryWithArgs(String sql, Object... args) throws Exception {
        if (args != null && args.length > 0) {
            throw new UnsupportedOperationException("数据湖查询端点未定义参数通道，拒绝静默丢弃绑定参数");
        }
        return query(sql);
    }

    /**
     * 把响应体解析成行集合。
     *
     * <p>契约：响应体必须是 JSON 数组，且每个元素是对象。其他形态（错误页、标量、
     * 对象包裹）都抛异常，不能返回空列表冒充"没有数据"。</p>
     *
     * @param body 响应体
     * @return 行列表，保序
     */
    public static List<Map<String, Object>> parseRows(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("查询响应为空");
        }
        JsonNode root;
        try {
            root = Json.getMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("查询响应不是合法 JSON: " + preview(body), e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("查询响应不是 JSON 数组: " + preview(body));
        }
        List<Map<String, Object>> rows = new ArrayList<>(root.size());
        TypeFactory types = Json.getMapper().getTypeFactory();
        for (JsonNode node : root) {
            if (!node.isObject()) {
                throw new IllegalArgumentException("查询响应含非对象元素: " + preview(body));
            }
            rows.add(Json.getMapper().convertValue(node,
                    types.constructMapType(LinkedHashMap.class, String.class, Object.class)));
        }
        return rows;
    }

    /**
     * 异常信息里回显的响应体片段。
     *
     * @param body 响应体
     * @return 片段
     */
    private static String preview(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        return trimmed.length() <= BODY_PREVIEW ? trimmed : trimmed.substring(0, BODY_PREVIEW) + "...";
    }
}
