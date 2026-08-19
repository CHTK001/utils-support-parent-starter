package com.chua.shardingv5.support.conversion;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * ShardingSphere 表结构缓存。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TableCache {

    /** 数据sources */
    private final List<DataSource> dataSources;
    /** 缓存秒 */
    private final int cacheSeconds;
    /** cache */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public TableCache(List<DataSource> dataSources, int cacheSeconds) {
        this.dataSources = dataSources;
        this.cacheSeconds = cacheSeconds;
    }

    public List<String> getTables(String prefix) {
        var now = System.currentTimeMillis();
        var entry = cache.get(prefix);
        if (entry != null && now < entry.expireAt) {
            return entry.tables;
        }
        // 缓存过期或不存在 → 重新扫描，按后缀降序（最新在前）
        var tables = scanTables(prefix);
        tables.sort((a, b) -> {
            var ma = Pattern.compile("\\d+$").matcher(a);
            var mb = Pattern.compile("\\d+$").matcher(b);
            if (ma.find() && mb.find()) {
                return Long.compare(Long.parseLong(mb.group()), Long.parseLong(ma.group()));
            }
            return b.compareTo(a);
        });
        cache.put(prefix, new CacheEntry(Collections.unmodifiableList(tables), now + cacheSeconds * 1000L));
        return tables;
    }

    public void clear() { cache.clear(); }

    private List<String> scanTables(String prefix) {
        var all = new LinkedHashSet<String>();
        var pat = Pattern.compile("^" + Pattern.quote(prefix) + ".+");
        for (var ds : dataSources) {
            try (var conn = ds.getConnection()) {
                var meta = conn.getMetaData();
                try (var rs = meta.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        var name = rs.getString("TABLE_NAME");
                        if (pat.matcher(name).matches()) {
                            all.add(name);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("扫描表失败: {}", e.getMessage());
            }
        }
        var result = new ArrayList<>(all);
        result.sort((a, b) -> {
            var ma = Pattern.compile("\\d+$").matcher(a);
            var mb = Pattern.compile("\\d+$").matcher(b);
            if (ma.find() && mb.find()) {
                return Long.compare(Long.parseLong(ma.group()), Long.parseLong(mb.group()));
            }
            return a.compareTo(b);
        });
        return result;
    }
}