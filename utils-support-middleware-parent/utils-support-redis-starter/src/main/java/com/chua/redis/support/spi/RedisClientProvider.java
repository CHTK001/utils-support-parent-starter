package com.chua.redis.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.middleware.MiddlewareCapability;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareDocument;
import com.chua.datasource.support.middleware.MiddlewareQueryResult;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import com.chua.datasource.support.middleware.MiddlewareTreeNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.params.ScanParams;

/**
 * Redis 中间件管理面 SPI 实现（默认装载）。
 *
 * <p>与 Kafka / InfluxDB / ZooKeeper 的差别只在装配方式：Redis 是基础能力，
 * 主应用**明文依赖**本模块，因此开箱即用；其余重型中间件按需引入或 OSGi 后装。
 * 代码路径完全相同（都是 {@link MiddlewareClientProvider} 的一个实现）。</p>
 *
 * <p><b>只读</b>：SCAN / INFO / DBSIZE / TYPE / TTL / CLIENT LIST / SLOWLOG GET。
 * DEL / FLUSHDB / CONFIG SET 等写操作一律不提供 —— 管理面定位是「看清现状」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "redis", order = 200)
public class RedisClientProvider implements MiddlewareClientProvider {

    /** SPI 名称，同时是注册文件名 */
    public static final String SPI_NAME = "com.chua.datasource.support.middleware.MiddlewareClientProvider";

    /** 单次 SCAN 批大小 */
    private static final int SCAN_COUNT = 100;

    /** 单次查询返回的 key 上限，避免把百万级 key 刷进响应体 */
    private static final int MAX_KEYS = 500;

    /** SCAN 起始游标：Jedis 7 的 ScanParams.SCAN_POINTER_START 不是 public，空串等价 */
    private static final String SCAN_START = "";

    /** 连接与读超时（毫秒） */
    private static final int TIMEOUT_MS = 5000;

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public String displayName() {
        return "Redis 缓存";
    }

    @Override
    public int defaultPort() {
        return 6379;
    }

    @Override
    public MiddlewareCapability capability() {
        return new MiddlewareCapability(
                true, true, true, true,
                "数据库 (DB0-15)", "键 (Keys)",
                "输入查询：dbsize / info / clients / slowlog / scan <pattern> / type <key> / ttl <key> / get <key>",
                "只读查询支持 dbsize（当前库键数）、info（服务器信息）、clients（客户端列表）、"
                        + "slowlog（慢查询）、scan <pattern>（按键名模式扫描）、type <key>（键类型）、"
                        + "ttl <key>（剩余过期时间）、get <key>（读字符串值）");
    }

    @Override
    public MiddlewareTestResult test(MiddlewareConnectRequest request) {
        try (Jedis jedis = jedis(request)) {
            String pong = jedis.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                return MiddlewareTestResult.fail("PING 未返回 PONG: " + pong);
            }
            String version = section(jedis.info("server"), "redis_version");
            String detail = "键总数=" + jedis.dbSize() + " 已用内存=" + section(jedis.info("memory"), "used_memory_human");
            return MiddlewareTestResult.ok(version, detail);
        } catch (Exception e) {
            return MiddlewareTestResult.fail(rootMessage(e));
        }
    }

    @Override
    public List<MiddlewareTreeNode> listTopNodes(MiddlewareConnectRequest request) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try (Jedis jedis = jedis(request)) {
            // 顶层节点 = 逻辑库；只把「有键的库」列出来，避免一堆空库占满界面
            int dbCount = 16;
            String keyspaces = jedis.info("keyspace");
            for (String line : keyspaces.split("\n")) {
                line = line.trim();
                if (!line.startsWith("db")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String name = line.substring(0, eq);
                Long count = keysOf(line.substring(eq + 1));
                out.add(new MiddlewareTreeNode(name, name, "", count));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    @Override
    public List<MiddlewareTreeNode> listChildren(MiddlewareConnectRequest request, String parentKey) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        // parentKey 形如 "db0"
        int index = dbIndex(parentKey);
        try (Jedis jedis = jedis(request)) {
            if (index > 0) {
                jedis.select(index);
            }
            ScanParams params = new ScanParams().count(SCAN_COUNT);
            String cursor = SCAN_START;
            int guard = 0;
            do {
                redis.clients.jedis.resps.ScanResult<String> r = jedis.scan(cursor, params);
                cursor = r.getCursor();
                for (String k : r.getResult()) {
                    if (out.size() >= MAX_KEYS) {
                        return out;
                    }
                    String type = jedis.type(k);
                    long ttl = jedis.ttl(k);
                    out.add(new MiddlewareTreeNode(k, k, type + (ttl >= 0 ? " · TTL " + ttl + "s" : ""), null));
                }
            } while (!SCAN_START.equals(cursor) && ++guard < 200);
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    @Override
    public MiddlewareQueryResult query(MiddlewareConnectRequest request, String expression, int limit) {
        if (expression == null || expression.isBlank()) {
            return MiddlewareQueryResult.fail("查询表达式为空。支持：dbsize / info / clients / slowlog / "
                    + "scan <pattern> / type <key> / ttl <key> / get <key>");
        }
        String cmd = expression.trim();
        String lower = cmd.toLowerCase();
        long t0 = System.currentTimeMillis();
        try (Jedis jedis = jedis(request)) {
            if (lower.equals("dbsize")) {
                return MiddlewareQueryResult.single("dbsize", jedis.dbSize());
            }
            if (lower.equals("info")) {
                return MiddlewareQueryResult.single("info", jedis.info());
            }
            if (lower.equals("clients")) {
                return MiddlewareQueryResult.single("clients", jedis.clientList());
            }
            if (lower.equals("slowlog")) {
                return MiddlewareQueryResult.single("slowlog", jedis.slowlogGet(128).toString());
            }
            if (lower.startsWith("scan ")) {
                return scan(jedis, t0, cmd.substring(5).trim(), limit);
            }
            if (lower.startsWith("type ")) {
                return MiddlewareQueryResult.single("type", jedis.type(cmd.substring(5).trim()));
            }
            if (lower.startsWith("ttl ")) {
                return MiddlewareQueryResult.single("ttl", jedis.ttl(cmd.substring(4).trim()));
            }
            if (lower.startsWith("get ")) {
                String key = cmd.substring(4).trim();
                String v = jedis.get(key);
                if (v == null) {
                    return MiddlewareQueryResult.fail("键不存在或不是字符串类型: " + key);
                }
                return MiddlewareQueryResult.single("value", v);
            }
            return MiddlewareQueryResult.fail("不支持的查询: " + cmd
                    + "。只读查询仅支持 dbsize / info / clients / slowlog / scan <pattern> / "
                    + "type <key> / ttl <key> / get <key>");
        } catch (Exception e) {
            return MiddlewareQueryResult.fail(rootMessage(e));
        }
    }

    @Override
    public MiddlewareDocument document(MiddlewareConnectRequest request, String scope, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try (Jedis jedis = jedis(request)) {
            String type = jedis.type(key);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("type", type);
            attrs.put("ttl", String.valueOf(jedis.ttl(key)));
            String body;
            switch (type) {
                case "string" -> body = String.valueOf(jedis.get(key));
                case "hash" -> body = jedis.hgetAll(key).toString();
                case "list" -> body = jedis.lrange(key, 0, 99).toString();
                case "set" -> body = jedis.smembers(key).toString();
                case "zset" -> body = jedis.zrangeWithScores(key, 0, 99).toString();
                default -> body = "(该类型不支持直接预览)";
            }
            return new MiddlewareDocument("键 " + key, body, attrs);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部实现 ====================

    /** 按数据源行建 Jedis 连接（用完即关，管理面请求稀疏） */
    private Jedis jedis(MiddlewareConnectRequest request) {
        DefaultJedisClientConfig.Builder b = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(TIMEOUT_MS)
                .socketTimeoutMillis(TIMEOUT_MS);
        int db = dbIndex(request.database());
        if (db > 0) {
            b.database(db);
        }
        String username = request.username();
        if (username != null && !username.isBlank()) {
            b.user(username);
        }
        String password = request.password();
        if (password != null && !password.isBlank()) {
            b.password(password);
        }
        return new Jedis(request.host(), request.port() <= 0 ? 6379 : request.port(), b.build());
    }

    /** 数据库名/编号 → 索引；非 db 形式返回 0 */
    private int dbIndex(String text) {
        if (text == null) {
            return 0;
        }
        String t = text.trim().toLowerCase();
        if (t.startsWith("db")) {
            t = t.substring(2);
        }
        try {
            return Math.max(0, Integer.parseInt(t));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** scan 查询：按模式扫键，返回键名 + 类型 + TTL */
    private MiddlewareQueryResult scan(Jedis jedis, long t0, String pattern, int limit) throws Exception {
        String match = pattern.isBlank() ? "*" : pattern;
        ScanParams params = new ScanParams().count(SCAN_COUNT).match(match);
        List<Map<String, Object>> rows = new ArrayList<>();
        String cursor = SCAN_START;
        int guard = 0;
        long total = 0L;
        do {
            redis.clients.jedis.resps.ScanResult<String> r = jedis.scan(cursor, params);
            cursor = r.getCursor();
            for (String k : r.getResult()) {
                total++;
                if (rows.size() >= Math.max(1, Math.min(limit, MAX_KEYS))) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", k);
                row.put("type", jedis.type(k));
                row.put("ttl", jedis.ttl(k));
                rows.add(row);
            }
        } while (!SCAN_START.equals(cursor) && ++guard < 200);
        return MiddlewareQueryResult.of(List.of("key", "type", "ttl"), rows, total > rows.size(), total,
                System.currentTimeMillis() - t0);
    }

    /** 从 keyspace 行里取 keys 数 */
    private Long keysOf(String spec) {
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "keys".equals(part.substring(0, eq).trim())) {
                try {
                    return Long.parseLong(part.substring(eq + 1).trim());
                } catch (NumberFormatException ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 从 INFO 的多节文本里取一个字段 */
    private String section(String info, String field) {
        if (info == null) {
            return "";
        }
        for (String line : info.split("\n")) {
            line = line.trim();
            if (line.startsWith(field + ":")) {
                return line.substring(field.length() + 1).trim();
            }
        }
        return "";
    }

    /** 取根因消息 */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
