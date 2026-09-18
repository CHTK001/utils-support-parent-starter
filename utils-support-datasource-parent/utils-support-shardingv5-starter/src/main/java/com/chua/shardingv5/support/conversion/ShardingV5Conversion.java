package com.chua.shardingv5.support.conversion;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.DataSourceConversion;
import com.chua.datasource.support.engine.DataSourceEnvironment;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.sharding.spi.ShardingAlgorithm;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
* 分库分表sphere V5 分库分表数据源转换器。
*
* <p>链式注册分片表 + 自定义算法，自动发现物理表。</p>
*
* <h3>使用示例</h3>
* <pre>{@code
* // 自定义算法实现
* new ShardingV5Conversion()
*     .algo("myMod", MyModAlgorithm.class)
*     .table("User", "user_id", 16, "myMod")
*     .convert(List.of(ds), env);
*
* // 内置算法（MOD / INTERVAL / HASH_MOD）
* new ShardingV5Conversion()
*     .algo("mod", "MOD")
*     .table("User", "user_id", 16, "mod")
*     .convert(List.of(ds), env);
*
* // 默认 MOD
* new ShardingV5Conversion()
*     .table("User", "user_id", 16)
*     .convert(List.of(ds), env);
*
* // 时间分片 + 自定义参数
* new ShardingV5Conversion()
*     .algo("tm", "INTERVAL", Map.of("datetime-pattern","yyyyMM","datetime-lower","2024-01-01"))
*     .table("Order", "create_time", 12, "tm")
*     .convert(List.of(ds), env);
*
* // 显式时间范围
* new ShardingV5Conversion()
*     .tableTimeRange("create_time", "2026-01-01", "2026-12-31",
*         List.of("User_202601", "User_202602"))
*     .convert(List.of(ds), env);
* }</pre>间范围
* new ShardingV5Conversion()
*     .tableTimeRange("create_time", "2026-01-01", "2026-12-31",
*         List.of("User_202601", "User_202602"))
*     .convert(List.of(ds), env);
* }</pre>
*
* @author CH
* @since 4.0.0.42
*/
@Spi("SHARDINGV5")
@Slf4j
public class ShardingV5Conversion implements DataSourceConversion {

    /** 表配置 */
    private final List<TableConfig> tableConfigs = new ArrayList<>();
    /** 数据库配置 */
    private final List<DbConfig> dbConfigs = new ArrayList<>();
    /** 时间rangeconfigs */
    private final List<TimeRangeConfig> timeRangeConfigs = new ArrayList<>();
    /** algorithms */
    private final Map<String, AlgorithmHolder> algorithms = new LinkedHashMap<>();
    /** Autodiscover */
    private boolean autoDiscover = true;
    /** 缓存秒 */
    private int cacheSeconds = -1;
    /** 表缓存 */
    private TableCache tableCache;

    /**
    * autodiscover
    *
    * @param auto auto
    * @return autoDiscover的结果
    */
    public ShardingV5Conversion autoDiscover(boolean auto) {
        this.autoDiscover = auto;
        return this;
    }

    /**
    * 设置表缓存 TTL。
    * <p>开启后，表发现结果会缓存指定秒数，过期后自动重新扫描。
    * 保证 DBA 新增表后能被自动感知。</p>
    *
    * @param seconds 缓存秒数，-1 表示不过期（默认）
    * @return 缓存的结果
    */
    public ShardingV5Conversion cache(int seconds) {
        this.cacheSeconds = seconds;
        return this;
    }

    /**
    * 注册自定义算法实现类。类_基础 要求类有无参构造器。
    * @param name 名称
    * @param implClass impl类
    * @return algo的结果
    */
    public ShardingV5Conversion algo(String name, Class<? extends ShardingAlgorithm> implClass) {
        try {
            ReflectUtils.instantiate(implClass);
        } catch (Exception e) {
            log.warn("算法类 " + implClass.getName() + " 缺少无参构造器，CLASS_BASED 运行时可能失败");
        }
        algorithms.put(name, new AlgorithmHolder("CLASS_BASED", Map.of(
                "class-name", implClass.getName(), "strategy", "STANDARD")));
        return this;
    }

    /**
    * 注册内置算法（MOD / 哈希_MOD / 间隔 等）。
    * @param name 名称
    * @param type 类型
    * @return algo的结果
    */
    public ShardingV5Conversion algo(String name, String type) {
        algorithms.put(name, new AlgorithmHolder(type, Map.of()));
        return this;
    }

    /**
    * 注册内置算法 + 自定义参数。
    * @param name 名称
    * @param type 类型
    * @param props props
    * @return algo的结果
    */
    public ShardingV5Conversion algo(String name, String type, Map<String, String> props) {
        algorithms.put(name, new AlgorithmHolder(type, props != null ? props : Map.of()));
        return this;
    }

    /**
    * 配置分库策略。
    *
    * @param prefix         逻辑表前缀
    * @param shardingColumn 分片字段
    * @param dbCount        分库数量
    * @param algorithm      库算法名称
    * @return db的结果
    */
    public ShardingV5Conversion db(String prefix, String shardingColumn, int dbCount, String algorithm) {
        dbConfigs.add(new DbConfig(prefix, shardingColumn, dbCount, algorithm));
        return this;
    }

    /**
    * 注册分片表。
    *
    * @param prefix         表名前缀
    * @param shardingColumn 分片字段
    * @param shardCount     分片数
    * @param algorithm      表算法名称
    * @return table的结果
    */
    public ShardingV5Conversion table(String prefix, String shardingColumn, int shardCount, String algorithm) {
        tableConfigs.add(new TableConfig(prefix, shardingColumn, shardCount, algorithm));
        return this;
    }

    /**
    * Table
    *
    * @param prefix 前缀
    * @param shardingColumn 分库分表column
    * @param shardCount shard数量
    * @return table的结果
    */
    public ShardingV5Conversion table(String prefix, String shardingColumn, int shardCount) {
        return table(prefix, shardingColumn, shardCount, "MOD");
    }

    /**
    * table时间范围
    * @param shardingColumn 分库分表column
    * @param start 启动
    * @param end 结束
    * @param realTables realtables
    */
    public ShardingV5Conversion tableTimeRange(String shardingColumn,
                                                String start, String end,
                                                List<String> realTables) {
        timeRangeConfigs.add(new TimeRangeConfig(shardingColumn, start, end, realTables));
        return this;
    }

    @Override
    /** 转换 */
    public DataSource convert(List<DataSource> dataSources, DataSourceEnvironment env) {
        if (dataSources.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个实际数据源");
        }
        var actualMap = new LinkedHashMap<String, DataSource>();
        var discovered = new LinkedHashMap<String, Set<String>>();
        var isCached = cacheSeconds > 0;

        for (int i = 0; i < dataSources.size(); i++) {
            var name = env != null && env.getPoolName() != null
                    ? env.getPoolName() + "_" + i : "ds_" + i;
            actualMap.put(name, dataSources.get(i));
            // 缓存模式下不预先扫描，避免浪费 JDBC 查询
            if (autoDiscover && !isCached) {
                discovered.put(name, discoverTables(dataSources.get(i)));
            }
        }

        var shardingRule = new ShardingRuleConfiguration();
        var allTables = autoDiscover && !isCached ? mergeDiscoveredTables(discovered) : new LinkedHashSet<String>();

 // 注册算法到 分库分表sphere
        for (var e : algorithms.entrySet()) {
            var h = e.getValue();
            var props = new Properties();
            if (h.props != null) {
                props.putAll(h.props);
            }
            if ("MOD".equalsIgnoreCase(h.type) && !props.containsKey("sharding-count")) {
                props.setProperty("sharding-count", "2");
            }
            shardingRule.getShardingAlgorithms().put(e.getKey(),
                    new AlgorithmConfiguration(h.type, props));
        }

        // 校验算法存在性，缺失时自动注册
        for (var tc : tableConfigs) {
            ensureAlgo(tc.algorithm, tc.shardCount);
        }
        for (var dc : dbConfigs) {
            ensureAlgo(dc.algorithm, dc.dbCount);
        }

 // 校验：重复 前缀 冲突
        var seenPrefixes = new HashSet<String>();
        for (var tc : tableConfigs) {
            if (!seenPrefixes.add(tc.prefix)) {
                log.warn("发现重复的分片表前缀: " + tc.prefix + "，后者将覆盖前者");
            }
        }

 // 初始化 table缓存
        if (isCached) {
            tableCache = new TableCache(dataSources, cacheSeconds);
        }

        // 发现表：缓存模式或扫描模式
        if (autoDiscover && !isCached) {
            for (var entry : discovered.entrySet()) {
                allTables.addAll(entry.getValue());
            }
        }

        // 非分片表
        if (autoDiscover) {
            Collection<String> known = isCached ? scanAllTables(dataSources) : allTables;
            for (var table : known) {
                var isSharded = tableConfigs.stream().anyMatch(c -> table.startsWith(c.prefix));
                var isTimeRanged = timeRangeConfigs.stream().anyMatch(c -> c.realTables.contains(table));
                if (!isSharded && !isTimeRanged) {
                    shardingRule.getTables().add(new ShardingTableRuleConfiguration(
                            table, buildDataNodes(table, actualMap.keySet())));
                }
            }
        }

        // 分片表
        for (var tc : tableConfigs) {
            List<String> matched;
            if (tableCache != null) {
                matched = tableCache.getTables(tc.prefix);
            } else {
                matched = allTables.stream()
                        .filter(t -> t.startsWith(tc.prefix))
                        .sorted((a, b) -> naturalCompare(b, a))
                        .collect(Collectors.toList());
            }
            if (matched.isEmpty()) {
                log.warn("未匹配到表: " + tc.prefix);
                continue;
            }

            var limited = matched.size() > tc.shardCount
                    ? matched.subList(0, tc.shardCount) : matched;
            var dbCfg = findDbConfig(tc.prefix);

            if (dbCfg != null) {
                // 分库 + 分表
                var dataNodes = actualMap.keySet().stream()
                        .flatMap(db -> limited.stream().map(tbl -> db + "." + tbl))
                        .collect(Collectors.joining(","));
                var config = new ShardingTableRuleConfiguration(tc.prefix, dataNodes);
                config.setDatabaseShardingStrategy(
                        new StandardShardingStrategyConfiguration(dbCfg.shardingColumn, dbCfg.algorithm));
                config.setTableShardingStrategy(
                        new StandardShardingStrategyConfiguration(tc.shardingColumn, tc.algorithm));
                shardingRule.getTables().add(config);
            } else {
 // 仅分表 → 设置 table分库分表strategy
                var dataNodes = limited.stream()
                        .flatMap(t -> actualMap.keySet().stream().map(ds -> ds + "." + t))
                        .collect(Collectors.joining(","));
                var config = new ShardingTableRuleConfiguration(tc.prefix, dataNodes);
                config.setTableShardingStrategy(
                        new StandardShardingStrategyConfiguration(tc.shardingColumn, tc.algorithm));
                shardingRule.getTables().add(config);
            }
        }

        // 时间范围分片
        for (int i = 0; i < timeRangeConfigs.size(); i++) {
            var tr = timeRangeConfigs.get(i);
            var algoName = "tr_algo_" + i;
            var dataNodes = tr.realTables.stream()
                    .flatMap(t -> actualMap.keySet().stream().map(ds -> ds + "." + t))
                    .collect(Collectors.joining(","));
            var config = new ShardingTableRuleConfiguration("tr_" + i + "_" + tr.shardingColumn, dataNodes);
            config.setDatabaseShardingStrategy(
                    new StandardShardingStrategyConfiguration(tr.shardingColumn, algoName));
            var props = new Properties();
            props.setProperty("datetime-lower", tr.start);
            props.setProperty("datetime-upper", tr.end);
            props.setProperty("datetime-partition-pattern", "yyyy-MM-dd");
            shardingRule.getShardingAlgorithms().put(algoName,
                    new AlgorithmConfiguration("INTERVAL", props));
            shardingRule.getTables().add(config);
        }

        try {
            return ShardingSphereDataSourceFactory.createDataSource(
                    "sharding_" + UUID.randomUUID().toString().substring(0, 8),
                    actualMap, List.of(shardingRule), new Properties());
        } catch (Exception e) {
            throw new RuntimeException("创建 ShardingSphere 数据源失败", e);
        }
    }

    @Override public String type() { return "SHARDINGV5"; }

    /**
    * 确保算法已注册；内置算法自动注册并设 分库分表-数量
    */
    private void ensureAlgo(String algoName, int count) {
        if (algorithms.containsKey(algoName)) {
            validateAlgo(algoName);
            return;
        }
        if (!isBuiltinAlgo(algoName)) {
            log.warn("算法 '" + algoName + "' 未注册，自动使用 MOD");
            ensureAlgo("MOD", count);
            return;
        }
        var props = new Properties();
        if ("MOD".equalsIgnoreCase(algoName) || "HASH_MOD".equalsIgnoreCase(algoName)) {
            props.setProperty("sharding-count", String.valueOf(count));
        }
        if ("INTERVAL".equalsIgnoreCase(algoName)) {
            log.warn("INTERVAL 算法需要配置 datetime-lower/upper/pattern，请在 .algo() 中提供");
        }
        algorithms.put(algoName, new AlgorithmHolder(algoName.toUpperCase(), toMap(props)));
        log.debug("自动注册算法: " + algoName + " (count=" + count + ")");
    }

    /**
    * 校验已注册的算法参数完整性
    * @param name 名称
    */
    private void validateAlgo(String name) {
        var h = algorithms.get(name);
        if (h == null) {
            return;
        }
        if ("INTERVAL".equalsIgnoreCase(h.type) && h.props != null) {
            if (!h.props.containsKey("datetime-lower") || !h.props.containsKey("datetime-upper")
                    || !h.props.containsKey("datetime-partition-pattern")) {
                log.warn("INTERVAL 算法 '" + name + "' 缺少 datetime-lower/upper/pattern 参数");
            }
        }
    }

    /**
    * 全量扫描所有数据源的表（给缓存模式下注册非分片表用）
    * @param dss dss
    * @return 扫描全部tables的结果
    */
    private Set<String> scanAllTables(List<DataSource> dss) {
        var all = new LinkedHashSet<String>();
        for (var ds : dss) {
            all.addAll(discoverTables(ds));
        }
        return all;
    }

    /**
    * 转为映射
    *
    * @param props props
    * @return 转为映射的结果
    */
    private Map<String, String> toMap(Properties props) {
        var map = new LinkedHashMap<String, String>();
        props.forEach((k, v) -> map.put((String) k, (String) v));
        return map;
    }

    /**
    * discovertables
    *
    * @param ds ds
    * @return discoverTables的结果
    */
    private Set<String> discoverTables(DataSource ds) {
        var tables = new LinkedHashSet<String>();
        try (var conn = ds.getConnection()) {
            var meta = conn.getMetaData();
            try (var rs = meta.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (Exception e) { log.warn("扫描表失败: " + e.getMessage()); }
        return tables;
    }

    /**
    * 合并discoveredtables
    *
    * @param discovered discovered
    * @return 合并discoveredtables的结果
    */
    private Set<String> mergeDiscoveredTables(Map<String, Set<String>> discovered) {
        var all = new LinkedHashSet<String>();
        discovered.values().forEach(all::addAll);
        return all;
    }

    /**
    * 构建数据节点
    *
    * @param table table
    * @param dsNames ds名称
    * @return 构建数据节点的结果
    * @author CH
    * @since 4.0.0
    */
    private String buildDataNodes(String table, Set<String> dsNames) {
        return dsNames.stream().map(ds -> ds + "." + table).collect(Collectors.joining(","));
    }

    static class TableConfig {
        final String prefix;
        final String shardingColumn;
        final int shardCount;
        final String algorithm;
        TableConfig(String p, String sc, int c, String a) {
            this.prefix = p;
            this.shardingColumn = sc;
            this.shardCount = c;
            this.algorithm = a;
        }
    }

    /**
    * 根据前缀查找对应的分库配置
    * @param prefix 前缀
    * @return finddb配置的结果
    */
    private DbConfig findDbConfig(String prefix) {
        return dbConfigs.stream().filter(d -> d.prefix.equals(prefix)).findFirst().orElse(null);
    }

    /**
    * 是否builtinalgo
    *
    * @param name 名称
    * @return 是否builtinalgo的结果
    */
    private boolean isBuiltinAlgo(String name) {
        return List.of("MOD", "HASH_MOD", "INTERVAL", "CLASS_BASED", "STANDARD",
                "INLINE", "COMPLEX_INLINE", "HINT_INLINE", "COSID", "SNOWFLAKE",
                "UUID", "NANOID").contains(name.toUpperCase());
    }

    /**
    * Natural比较
    *
    * @param a a
    * @param b b
    * @return naturalCompare的结果
    */
    private int naturalCompare(String a, String b) {
        return extractSuffix(a).compareTo(extractSuffix(b));
    }

    /**
    * extract后缀
    *
    * @param name 名称
    * @return extract后缀的结果
    */
    private String extractSuffix(String name) {
        var m = java.util.regex.Pattern.compile("\\d+$").matcher(name);
        if (m.find()) {
            return m.group();
        }
        return name;
    }
}
