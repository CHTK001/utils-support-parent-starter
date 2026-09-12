package com.chua.redis.support.datasource;

import com.chua.datasource.support.datasource.DataTable;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;
import java.util.stream.Collectors;

/**
* Redis 数据表实现，将 Redis 哈希 数据映射为表格结构。
* <p>
* 每个 Redis 键 对应一行数据，哈希 字段对应列。
* 支持通过 键 模式匹配（如 {@code user:*}) 扫描数据。
* 当前为只读实现，用于 Calcite 查询聚合。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class RedisDataTable implements DataTable {

    /**
    * 表名
     */
    private final String name;

    /**
    * Redis 连接池
     */
    private final JedisPool jedisPool;

    /**
    * 键 模式（如 用户:*）
     */
    private final String keyPattern;

    /**
    * 列名列表
     */
    private final List<String> columnNames;

    /**
    * 列类型列表
     */
    private final List<Class<?>> columnTypes;

    /**
    * 行数据缓存
     */
    private final List<Map<String, Object>> rows;

    /**
    * 是否已初始化
     */
    private boolean initialized;

    // ---------------------------------------------------------------
    // 构造
    // ---------------------------------------------------------------

    /**
    * 创建 Redis 数据table。
    *
    * @param name       表名
    * @param jedisPool  Redis 连接池
    * @param keyPattern 键 匹配模式（如 {@code user:*})
     */
    public RedisDataTable(String name, JedisPool jedisPool, String keyPattern) {
        this.name = name;
        this.jedisPool = jedisPool;
        this.keyPattern = keyPattern;
        this.columnNames = new ArrayList<>();
        this.columnTypes = new ArrayList<>();
        this.rows = new ArrayList<>();
        this.initialized = false;
    }

    // ---------------------------------------------------------------
    // 初始化（延迟加载）
    // ---------------------------------------------------------------

    /**
    * 扫描 Redis 并加载数据。
     */
    private void ensureLoaded() {
        if (initialized) {
            return;
        }
        Set<String> columnSet = new LinkedHashSet<>();
        List<Map<String, Object>> dataRows = new ArrayList<>();

        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = "0";
            do {
                var scanResult = jedis.scan(cursor,
                        new redis.clients.jedis.params.ScanParams().match(keyPattern).count(100));
                cursor = scanResult.getCursor();

                for (String key : scanResult.getResult()) {
                    Map<String, String> hash = jedis.hgetAll(key);
                    if (hash.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("_key", key);
                    for (Map.Entry<String, String> entry : hash.entrySet()) {
                        columnSet.add(entry.getKey());
                        row.put(entry.getKey(), entry.getValue());
                    }
                    dataRows.add(row);
                }
            } while (!"0".equals(cursor));
        } catch (Exception e) {
            log.warn("读取 Redis 数据失败: keyPattern={}", keyPattern, e);
        }

 // 补充 _键 列
        List<String> cols = new ArrayList<>();
        cols.add("_key");
        cols.addAll(columnSet);

        this.columnNames.addAll(cols);
        for (int i = 0; i < cols.size(); i++) {
            this.columnTypes.add(cols.get(i).equals("_key") ? String.class : String.class);
        }
        this.rows.addAll(dataRows);
        this.initialized = true;

        log.debug("Redis DataTable [{}] 已加载: {} 列, {} 行", name, columnNames.size(), rows.size());
    }

    // ---------------------------------------------------------------
 // 数据table 接口
    // ---------------------------------------------------------------

    @Override
    /** 获取名称 */
    public String getName() {
        return name;
    }

    @Override
     /**
     * 获取column名称。
     * @return 获取column名称的结果
      */
     * 获取column名称
     *
     * @return 获取column类型的结果
     */
    public List<String> getColumnNames() {
        ensureLoaded();
        return Collections.unmodifiableList(columnNames);
    }

    public List<Class<?>> getColumnTypes() {
        ensureLoaded();
        return Collections.unmodifiableList(columnTypes);
    }

    @Override
    /** 获取数据 */
    public List<Map<String, Object>> getData() {
        ensureLoaded();
        return Collections.unmodifiableList(rows);
    }

    @Override
    /** 获取Row计算数量 */
    public long getRowCount() {
        ensureLoaded();
        return rows.size();
    }

    @Override
    /** 转为字符串 */
    public String toString() {
        return "RedisDataTable{" +
                "name='" + name + '\'' +
                ", keyPattern='" + keyPattern + '\'' +
                ", rows=" + (initialized ? rows.size() : "?") +
                '}';
    }
}
