package com.chua.cloudflare.support.d1;

import java.util.LinkedHashMap;
import java.util.Map;

/**
* 单条 SQL 语句 + 参数。
*
* @author CH
* @since 4.0.0.42
* @param sql SQL
* @param params 参数
* @return d1对账单的结果
 */
public record D1Statement(String sql, D1SqlParameter params) {

    public D1Statement {
        params = params == null ? D1SqlParameter.ofPositional(new Object[0]) : params;
    }

    /**
    * 用位置参数构造。
    *
    * @param sql    SQL
    * @param params 参数
    * @return D1Statement
    */
    public static D1Statement of(String sql, Object... params) {
        return new D1Statement(sql, D1SqlParameter.ofPositional(params));
    }

    /**
    * 序列化为 D1 API 接受的 JSON 节点。
    *
    * <p>{@code {"sql": "...", "params": [...]}}；无参数时省略 params。
    * 命名参数（{@code :name}）按 SQL 中出现顺序转换为数组。</p>
    *
    * @return JSON 节点（映射 形式）
    */
    public Map<String, Object> toJson() {
        var map = new LinkedHashMap<String, Object>();
        map.put("sql", sql);
        if (params.hasParams()) {
            map.put("params", params.toJson(sql));
        }
        return map;
    }
}
