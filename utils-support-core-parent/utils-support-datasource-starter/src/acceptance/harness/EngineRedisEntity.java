import com.chua.datasource.support.annotation.TableName;
import com.chua.redis.support.engine.RediSearchEngine;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 引擎验收运行器 —— 阶段十六：{@code RediSearchEngine}（SPI {@code redis}）。
 *
 * <p>阶段三/五已覆盖 {@code RedisClient}（KvEngine）与 {@code RedisSeriesEngine}，
 * 但 SPI 键为 {@code redis} 的 {@code RediSearchEngine} 的<b>实体 CRUD</b> 从未被验收。
 * 本阶段用本机真实 Redis（{@code 127.0.0.1:6379}）做读写验收。</p>
 *
 * <p>本阶段锁定三个真实缺陷的修复：</p>
 * <ul>
 *   <li>缺陷 15：key 前缀曾用 {@code getSimpleName().toLowerCase()} 推导，绕过
 *       {@code @TableName} 与驼峰转下划线 —— {@code store("t_acc_user", ...)} 写入的数据
 *       在 {@code query(AccUser.class)} 下读不出来（回退扫描按 {@code accuser:*} 找键）；</li>
 *   <li>缺陷 16：{@code FT.SEARCH} 不可用时回退到全量扫描，但<b>丢掉了 WHERE 条件</b> ——
 *       带条件的查询会静默返回全部行，比报错更危险；</li>
 *   <li>缺陷 17：{@code query(X).count()} 未覆写，直接抛 {@code UnsupportedOperationException}。</li>
 * </ul>
 *
 * <p>本机 Redis 不支持 {@code MODULE} 命令（无 RediSearch 模块），因此所有查询都会走
 * 内存回退路径 —— 这正好让缺陷 16/17 可被真实复现与回归。</p>
 */
public final class EngineRedisEntity {

    /** 无 {@code @TableName} 的实体：用于验证驼峰转下划线。 */
    public static class PlainUser {
        private Long id;
        private String name;

        public PlainUser() {
        }

        public PlainUser(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** 带 {@code @TableName} 的实体（复用验收公共实体）。 */
    @TableName("t_redis_user")
    public static class RedisUser {
        private Long id;
        private String name;
        private Integer age;

        public RedisUser() {
        }

        public RedisUser(Long id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;
    private static final String TABLE = "t_redis_user";
    private static final String PLAIN_TABLE = "plain_user";

    private EngineRedisEntity() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十六：RediSearchEngine 实体 CRUD（真实 Redis 6379）");
        EngineFunctional.emit("=".repeat(78));

        if (!redisReachable()) {
            EngineFunctional.recordFail("阶段十六需要本机 Redis（127.0.0.1:6379），当前不可达");
            EngineFunctional.finishAndExit("EngineRedisEntity-report.txt");
            return;
        }

        tableSuite();
        querySuite();
        countSuite();
        pageSuite();
        capabilitySuite();

        EngineFunctional.finishAndExit("EngineRedisEntity-report.txt");
    }

    // ==================== 表名解析（缺陷 15 回归锁） ====================

    private static void tableSuite() {
        EngineFunctional.emit("--- redis.table ---");

        EngineFunctional.check("table.@TableName 生效：store 与 query 使用同一前缀", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                Set<String> keys = keys(TABLE + ":*");
                if (!keys.contains(TABLE + ":1")) {
                    throw new EngineFunctional.HarnessFailure(
                            "写入后未找到键 " + TABLE + ":1，实际键=" + keys);
                }
                List<RedisUser> rows = e.query(RedisUser.class).list();
                if (rows.size() != 3) {
                    throw new EngineFunctional.HarnessFailure(
                            "store 写入 3 行，query 只读到 " + rows.size()
                                    + " 行（缺陷 15：key 前缀与实体表名不一致）");
                }
                return "keys=" + keys.size() + ", rows=" + rows.size();
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("table.无 @TableName：驼峰转下划线（plain_user）", () -> {
            cleanup(PLAIN_TABLE);
            RediSearchEngine e = engine();
            try {
                List<PlainUser> data = new ArrayList<>();
                data.add(new PlainUser(1L, "alice"));
                data.add(new PlainUser(2L, "bob"));
                e.store(PLAIN_TABLE, data);
                Set<String> keys = keys(PLAIN_TABLE + ":*");
                if (keys.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure(
                            "未找到 " + PLAIN_TABLE + ":* 键，实际=" + keys("plainuser:*"));
                }
                List<PlainUser> rows = e.query(PlainUser.class).list();
                if (rows.size() != 2) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望读到 2 行，实际 " + rows.size());
                }
                return "prefix=" + PLAIN_TABLE + ", rows=" + rows.size();
            } finally {
                e.close();
                cleanup(PLAIN_TABLE);
            }
        });

        EngineFunctional.check("table.字段映射：Long/String/Integer 正确还原", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                List<RedisUser> rows = e.query(RedisUser.class).eq(RedisUser::getId, 1L).list();
                if (rows.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("按 id=1 期望 1 行，实际 " + rows.size());
                }
                RedisUser u = rows.get(0);
                if (!Long.valueOf(1L).equals(u.getId()) || !"alice".equals(u.getName())
                        || !Integer.valueOf(30).equals(u.getAge())) {
                    throw new EngineFunctional.HarnessFailure(
                            "映射结果错误: id=" + u.getId() + " name=" + u.getName() + " age=" + u.getAge());
                }
                return u.getId() + "/" + u.getName() + "/" + u.getAge();
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });
    }

    // ==================== 条件查询（缺陷 16 回归锁） ====================

    private static void querySuite() {
        EngineFunctional.emit("--- redis.query ---");

        EngineFunctional.check("query.单条件过滤（回退扫描不得返回全部行）", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                List<RedisUser> rows = e.query(RedisUser.class).eq(RedisUser::getName, "alice").list();
                if (rows.size() != 1) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望 1 行（name=alice），实际 " + rows.size()
                                    + " 行（缺陷 16：回退扫描忽略了 WHERE 条件）");
                }
                if (!"alice".equals(rows.get(0).getName())) {
                    throw new EngineFunctional.HarnessFailure("命中行不是 alice: " + rows.get(0).getName());
                }
                return "rows=1, name=alice";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("query.多条件 AND 过滤", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                int hit = e.query(RedisUser.class)
                        .eq(RedisUser::getName, "alice")
                        .eq(RedisUser::getAge, 30)
                        .list().size();
                int miss = e.query(RedisUser.class)
                        .eq(RedisUser::getName, "alice")
                        .eq(RedisUser::getAge, 31)
                        .list().size();
                if (hit != 1 || miss != 0) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望命中 1 / 0，实际 " + hit + " / " + miss);
                }
                return "hit=1, miss=0";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("query.无命中返回空列表（不是全量）", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                List<RedisUser> rows = e.query(RedisUser.class).eq(RedisUser::getName, "nobody").list();
                if (!rows.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望空列表，实际 " + rows.size() + " 行");
                }
                return "empty";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("query.one() 返回唯一命中行", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                RedisUser u = e.query(RedisUser.class).eq(RedisUser::getName, "bob").one();
                if (u == null || !"bob".equals(u.getName())) {
                    throw new EngineFunctional.HarnessFailure("one() 未返回 bob: " + u);
                }
                return "one=bob";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("query.无条件的 list() 返回全部 3 行", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                int n = e.query(RedisUser.class).list().size();
                if (n != 3) {
                    throw new EngineFunctional.HarnessFailure("期望 3 行，实际 " + n);
                }
                return "rows=3";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });
    }

    // ==================== count（缺陷 17 回归锁） ====================

    private static void countSuite() {
        EngineFunctional.emit("--- redis.count ---");

        EngineFunctional.check("count.全量计数为 3，且不再抛 UOE", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                long n = e.query(RedisUser.class).count();
                if (n != 3L) {
                    throw new EngineFunctional.HarnessFailure("期望 3，实际 " + n);
                }
                return "count=3";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("count.条件计数为 1（与过滤语义一致）", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                long n = e.query(RedisUser.class).eq(RedisUser::getName, "alice").count();
                if (n != 1L) {
                    throw new EngineFunctional.HarnessFailure("期望 1，实际 " + n);
                }
                return "count=1";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("count.无命中为 0", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                long n = e.query(RedisUser.class).eq(RedisUser::getName, "nobody").count();
                if (n != 0L) {
                    throw new EngineFunctional.HarnessFailure("期望 0，实际 " + n);
                }
                return "count=0";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });
    }

    // ==================== 分页 ====================

    private static void pageSuite() {
        EngineFunctional.emit("--- redis.page ---");

        EngineFunctional.check("page(1,2) 的 total=3、当前页 2 行（SCAN 顺序无关）", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                var page = e.query(RedisUser.class).page(1, 2);
                if (page.getTotal() != 3L || page.getRecords().size() != 2) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望 total=3/2 行，实际 total=" + page.getTotal()
                                    + "/" + page.getRecords().size() + " 行");
                }
                return "total=3, records=2";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("page(2,2) 的 total 仍为 3，且只返回剩余行", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                var page = e.query(RedisUser.class).page(2, 2);
                if (page.getTotal() != 3L) {
                    throw new EngineFunctional.HarnessFailure("期望 total=3，实际 " + page.getTotal());
                }
                if (page.getRecords().size() != 1) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望第 2 页 1 行，实际 " + page.getRecords().size());
                }
                return "total=3, records=1";
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });
    }

    // ==================== 能力声明与生命周期 ====================

    private static void capabilitySuite() {
        EngineFunctional.emit("--- redis.capability ---");

        EngineFunctional.check("capability.update 明确抛 UOE（不静默失败）", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                try {
                    e.update(RedisUser.class).eq(RedisUser::getName, "alice").set(
                            RedisUser::getName, "x").update();
                    throw new EngineFunctional.HarnessFailure("update 未抛 UOE");
                } catch (UnsupportedOperationException expected) {
                    return expected.getMessage();
                }
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("capability.delete 明确抛 UOE（不静默失败）", () -> {
            cleanup(TABLE);
            RediSearchEngine e = engine();
            try {
                e.store(TABLE, sampleUsers());
                try {
                    e.delete(RedisUser.class).eq(RedisUser::getName, "alice").remove();
                    throw new EngineFunctional.HarnessFailure("delete 未抛 UOE");
                } catch (UnsupportedOperationException expected) {
                    return expected.getMessage();
                }
            } finally {
                e.close();
                cleanup(TABLE);
            }
        });

        EngineFunctional.check("capability.无执行器 → supportsSql=false，querySql 抛 UOE", () -> {
            RediSearchEngine e = engine();
            try {
                if (e.getExecutor() != null) {
                    throw new EngineFunctional.HarnessFailure("getExecutor() 应为 null");
                }
                if (e.supportsSql()) {
                    throw new EngineFunctional.HarnessFailure("无执行器却声明 supportsSql=true");
                }
                try {
                    e.querySql("GET k");
                    throw new EngineFunctional.HarnessFailure("querySql 未抛 UOE");
                } catch (UnsupportedOperationException expected) {
                    return "supportsSql=false";
                }
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("lifecycle.close() 幂等（重复调用不抛）", () -> {
            RediSearchEngine e = engine();
            e.close();
            e.close();
            return "closed twice";
        });

        EngineFunctional.check("lifecycle.原生命令 execute() 可执行（PING 之外的 SET/DEL）", () -> {
            RediSearchEngine e = engine();
            String key = "acc_cmd_" + System.currentTimeMillis();
            try {
                int affected = e.execute("DEL " + key);
                return "del=" + affected;
            } finally {
                e.close();
                try (Jedis jedis = new Jedis(HOST, PORT)) {
                    jedis.del(key);
                }
            }
        });
    }

    // ==================== 基础设施 ====================

    private static RediSearchEngine engine() {
        RediSearchEngine engine = new RediSearchEngine();
        engine.addDataSource("default", HOST, PORT);
        return engine;
    }

    private static List<RedisUser> sampleUsers() {
        List<RedisUser> data = new ArrayList<>();
        data.add(new RedisUser(1L, "alice", 30));
        data.add(new RedisUser(2L, "bob", 25));
        data.add(new RedisUser(3L, "carol", 41));
        return data;
    }

    private static boolean redisReachable() {
        try (Jedis jedis = new Jedis(HOST, PORT)) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    private static Set<String> keys(String pattern) {
        try (Jedis jedis = new Jedis(HOST, PORT)) {
            return jedis.keys(pattern);
        }
    }

    private static void cleanup(String prefix) {
        try (Jedis jedis = new Jedis(HOST, PORT)) {
            Set<String> keys = jedis.keys(prefix + ":*");
            if (!keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        }
    }
}
