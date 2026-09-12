package com.chua.common.support.config.source;

import com.chua.common.support.collection.LiteRawMap;
import lombok.Getter;

import java.util.Map;

/**
* 基于 LiteRawMap 的属性源
*
* <p>内部统一使用 {@link LiteRawMap} 存储，一份数据同时支持嵌套取值和扁平取值。
* 避免了原始 Map 和 toMap() 拷贝之间的数据冗余。
*
* @author CH
* @since 2023-09-07
 */
@Getter
public class MapPropertySource extends AbstractPropertySource {

    /**
    * 内部存储（LiteRawMap 封装原始 Map）
     */
    private final LiteRawMap properties;

    /**
    * 构造方法
    *
    * @param name   属性源名称
    * @param source 原始 Map 数据
     */
    public MapPropertySource(String name, Map<String, Object> source) {
        super(name);
        this.properties = source instanceof LiteRawMap lrm ? lrm : LiteRawMap.of(source);
    }

    /**
    * 设置属性值
     */
    public void setProperty(String key, Object value) {
        if (key != null) {
            properties.put(key, value);
        }
    }

    /**
    * 获取原始属性值
    *
    * <p>先精确匹配 key，找不到再尝试嵌套穿透。
    * 这样同时兼容扁平 key（如 "server.port"=8080）和嵌套 key（如 server.port → 8080）。
    *
    * <p>示例：
    * <pre>
    *   原始数据: {"server.port": 8080}           → get("server.port") = 8080, get("server") = null
    *   嵌套数据: {"server": {"port": 8080}}      → get("server.port") = 8080, get("server") = Map
    *   混合数据: {"server.port": 8080, "server": {"host": "localhost"}}
    *             → get("server.port") = 8080 (精确匹配优先)
    *             → get("server") = Map (精确匹配)
    *             → get("server.host") = "localhost" (嵌套穿透)
    * </pre>
    *
    * @param key 属性键
    * @return 属性值
     */
    @Override
    protected Object getRawProperty(String key) {
        if (key == null) { return null; }
        // 1. 精确匹配：key 完全一致
        Object exact = properties.get(key);
        if (exact != null) { return exact; }
        // 2. 嵌套穿透：server.port → server.get("port")
        return properties.getDot(key);
    }

    /**
    * 获取底层 LiteRawMap（不拷贝，直接引用）
     */
    @Override
    protected Object getSource() {
        return properties;
    }

}